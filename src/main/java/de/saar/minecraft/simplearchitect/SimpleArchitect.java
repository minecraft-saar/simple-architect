package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.IntroductionMessage;
import de.saar.coli.minecraft.relationextractor.Relation.Orientation;
import de.saar.minecraft.analysis.WeightEstimator;
import de.saar.minecraft.architect.AbstractArchitect;
import de.saar.minecraft.shared.BlockDestroyedMessage;
import de.saar.minecraft.shared.BlockPlacedMessage;
import de.saar.minecraft.shared.NewGameState;
import de.saar.minecraft.shared.StatusMessage;
import de.saar.minecraft.shared.WorldSelectMessage;
import de.up.ling.tree.Tree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import umd.cs.shop.costs.CostFunction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.lang.Math.abs;
import static java.lang.Math.random;

public class SimpleArchitect extends AbstractArchitect {
    
    private static class InstructionTuple {
        public final String instruction;
        public final Tree<String> tree;
        /** marks whether we instruct the object for the first time.*/
        public boolean isNewInstruction;

        public InstructionTuple(String instruction, Tree<String> tree, boolean isNewInstruction) {
            this.instruction = instruction;
            this.tree = tree;
            this.isNewInstruction = isNewInstruction;
        }

        public String toJson() {
            String treeString;
            if (tree == null) {
                treeString = "NULL";
            } else {
                treeString = tree.toString();
            }
            return "{\"message\":\"" +
                    instruction +
                    "\",\"tree\":\"" +
                    treeString +
                    "\",\"new\":" +
                    isNewInstruction +
                    "}";
        }
    }

    private static final Logger logger = LogManager.getLogger(SimpleArchitect.class);
    private static final int RESEND_INTERVAL = 12000;
    private static final int MESSAGE_PAUSE = 500;

    protected PlanCreator planCreator;
    
    protected HashSet<MinecraftObject> it = new HashSet<>();
    private List<MinecraftObject> plan;
    private Set<Block> currentInstructionBlocksLeft = Set.of();
    protected Set<MinecraftObject> world;
    protected MinecraftRealizer realizer;
    protected AtomicInteger numBlocksPlaced = new AtomicInteger(0);
    protected AtomicLong lastUpdate = new AtomicLong(0);
    protected InstructionTuple currentInstruction;
    protected Orientation lastOrientation = Orientation.ZMINUS;
    protected String scenario;
    private final CountDownLatch readyCounter = new CountDownLatch(1);
    private final CountDownLatch objectiveSet = new CountDownLatch(1);
    private long startTime;
    private boolean SecretWordThreadStarted = false;
    private int numCorrectBlocks = 0;
    protected WeightEstimator.WeightResult weights;

    private final SimpleArchitectConfiguration config;

    /** These are all blocks that exist in the world (except water block)*/
    protected Set<Block> alreadyPlacedBlocks = new HashSet<>();
    /** These are blocks that were placed but we did not instruct them (yet)
    They might be part of what we want to instruct later on though.*/
    protected Set<Block> incorrectlyPlacedBlocks = new HashSet<>();

    public SimpleArchitect(SimpleArchitectConfiguration config) {
        this.config = config;
        this.realizer = MinecraftRealizer.createRealizer();
        List<List<Tree<String>>> seedGames = new ArrayList<>();
        if (config.getAddSeedGames()) {
            seedGames = this.generateSeedInstructionTrees();
        }
        switch (config.getWeightSource()) {
            case "random":
                this.realizer.randomizeExpectedDurations();
                break;
            case "UCB":
                weights = new WeightEstimator(config.getWeightTrainingDatabase(),
                        config.getWeightTrainingDBUser(),
                        config.getWeightTrainingDBPassword(),
                        config.getTrainingSamplingLowerPercentile(),
                        config.getTrainingSamplingUpperPercentile(),
                        seedGames,
                        config.getWeightTrainingArchitectName(),
                        config.getDeletionsAsCosts())
                        .getUCBWithBootstrap(config.getTrainingNumBootstrapRuns(), true);
                realizer.setExpectedDurations(weights.weights, false);
                break;
            case "bootstrapped":
                weights = new WeightEstimator(config.getWeightTrainingDatabase(),
                        config.getWeightTrainingDBUser(),
                        config.getWeightTrainingDBPassword(),
                        config.getTrainingSamplingLowerPercentile(),
                        config.getTrainingSamplingUpperPercentile(),
                        seedGames,
                        config.getWeightTrainingArchitectName(),
                        config.getDeletionsAsCosts())
                        .sampleDurationCoeffsWithBootstrap(config.getTrainingNumBootstrapRuns(), true);
                realizer.setExpectedDurations(weights.weights, false);
                break;
            case "epsilongreedy":
                if (random() > config.getEpsilonGreedyPercentage()) {
                    this.realizer.randomizeExpectedDurations();
                    break;
                } // else: take optimal case below
            case "optimal":
                weights = new WeightEstimator(config.getWeightTrainingDatabase(),
                        config.getWeightTrainingDBUser(),
                        config.getWeightTrainingDBPassword(),
                        config.getTrainingSamplingLowerPercentile(),
                        config.getTrainingSamplingUpperPercentile(),
                        seedGames,
                        config.getWeightTrainingArchitectName(),
                        config.getDeletionsAsCosts())
                    .predictDurationCoeffsFromAllGames();
                realizer.setExpectedDurations(weights.weights, false);
                break;
            case "file":
                try {
                    weights = WeightEstimator.WeightResult.fromJson(
                            Files.readString(Paths.get(config.getWeightFile())));
                    realizer.setExpectedDurations(weights.weights, false);
                } catch (IOException e) {
                    throw new RuntimeException("could not read weights file: " + config.getWeightFile());
                }
            case "default":
                break;
            default:
                throw new RuntimeException("unknown value \"" + config.getWeightSource() + "\" for weightSource. "
                + "valid values: random, bootstrapped, optimal, default");
        }
    }

    @Override
    public synchronized void playerReady() {
        startTime = java.lang.System.currentTimeMillis();
        logger.debug("received playerReady");
        sendMessage("Welcome! I will try to instruct you to build a " + scenario);
        // these information will be given externally.
        // sendMessage("you can move around with w,a,s,d and look around with your mouse.");
        // sendMessage("Place blocks with the RIGHT mouse button, delete with LEFT mouse button.");
        sendMessage("press spacebar twice to fly and shift to dive.");
        sendMessage("If you place a block at the correct position, it will appear as stone bricks.");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
        // This is the first time we can log something
        // log the complete plan and the current object.
        try {
            readyCounter.await();
        } catch (InterruptedException e) {
            // TODO
            e.printStackTrace();
        }
        String planString = "[" + plan.stream()
                .map(MinecraftObject::asJson)
                .collect(Collectors.joining(",\n")) + "]";
        if (weights != null) {
            log(weights.toJson(), "GrammarWeights");
        } else {
            log(realizer.getWeightsAsJson(), "GrammarWeights");
        }
        log(planString, "InitialPlan");
        var instructions = computeNextInstructions();
        sendMessages(instructions, false);
        objectiveSet.countDown();
    }

    private static InputStream getResourceStream(String resName) {
        return SimpleArchitect.class.getResourceAsStream(resName);
    }

    /**
     * computes the plan and initializes the world.
     */
    @Override
    public synchronized void initialize(WorldSelectMessage message) {
        logger.debug(() -> "initializing with " + message);
        setGameId(message.getGameId());
        scenario = message.getName();
        String instructionlevel = config.getInstructionlevel();
        if ( ! config.getPlanFile().isEmpty()) {
            planCreator = new PlanCreatorFromFile(scenario,
                    config.getPlanFile());
        } else {
            if (instructionlevel.equals("adaptive")) {
                planCreator = getOptimalPlan(scenario);
            } else {
                planCreator = new PlanCreator(scenario, CostFunction.InstructionLevel.valueOf(instructionlevel));
            }
        }
        this.plan = planCreator.getPlan();
        this.world = planCreator.getInitialWorld();
        this.alreadyPlacedBlocks = planCreator.getBlocksCurrentWorld();
        logger.debug("initialization done");
        readyCounter.countDown();
    }

    public PlanCreator getOptimalPlan(String scenario) {
        PlanCreator argmin = null;
        double min = Double.POSITIVE_INFINITY;
        for (var il : CostFunction.InstructionLevel.values()) {
            logger.warn("trying instruction level " +  il);
            var planCreator = new PlanCreator(scenario, il);
            double cost = getCostForPlanCreator(planCreator);
            logger.warn("cost: " + cost);
            if (cost < min) {
                argmin = planCreator;
                min = cost;
            }
        }
        return argmin;
    }

    protected List<List<Tree<String>>> generateSeedInstructionTrees() {
        List<List<Tree<String>>> result = new ArrayList<>();
        for (String currScenario: List.of("house", "bridge")) {
            for (var il : CostFunction.InstructionLevel.values()) {
                logger.debug("trying instruction level " + il);
                var planCreator = new PlanCreator(currScenario, il);
                result.add(generateSeedInstructionTrees(planCreator));
            }
        }
        return result;
    }
    
    protected List<Tree<String>> generateSeedInstructionTrees(PlanCreator planCreator) {
        var result = new ArrayList<Tree<String>>();
        var tmpplan = planCreator.getPlan();
        var tmpworld = planCreator.getInitialWorld();

        Set<MinecraftObject> it = new HashSet<>();
        Set<String> knownOjbectTypes = new HashSet<>();
        for (var mco: tmpplan) {
            if (mco instanceof IntroductionMessage) {
                var im = (IntroductionMessage) mco;
                if (! im.starting) {
                    tmpworld.add(im.object);
                    it.add(im.object);
                    knownOjbectTypes.add(((IntroductionMessage) mco).object.getClass().getSimpleName().toLowerCase());
                }
                continue;
            }
            String currentObjectType = mco.getClass().getSimpleName().toLowerCase();
            boolean objectFirstOccurence = ! knownOjbectTypes.contains(currentObjectType);
            var tree = realizer.generateReferringExpressionTree(tmpworld, mco, it, Orientation.ZMINUS);
            if (tree != null) {
                result.add(tree);
            } else {
                logger.warn("tree is null for object " + mco.toString());
                logger.warn("world: " + toJson(tmpworld));
                logger.warn("it: " + toJson(it));
            }

            tmpworld.add(mco);
            tmpworld.addAll(mco.getBlocks());
            it.clear();
            it.add(mco);
            if (objectFirstOccurence) {
                knownOjbectTypes.add(currentObjectType);
            }
        }
        return result;
    }
    
    protected double getCostForPlanCreator(PlanCreator planCreator) {
        return getCostForPlanCreator(planCreator, false);
    }
    /**
     * Returns the predicted cost (in seconds) to fulfill the plan created by {@code planCreator}.
     * The cost is the negative weight of all derivation trees for the plans;
     * introduction messages are ignored.
     */
    protected double getCostForPlanCreator(PlanCreator planCreator, boolean printInstructions) {
        logger.debug("computing cost for " + planCreator.getInstructionLevel());
        var tmpplan = planCreator.getPlan();
        var tmpworld = planCreator.getInitialWorld();
        double totalCost = 0;
        HashSet<MinecraftObject> it = new HashSet<>();
        Set<String> knownOjbectTypes = new HashSet<>();
        for (var mco: tmpplan) {
            if (mco instanceof IntroductionMessage) {
                var im = (IntroductionMessage)mco;
                if (! im.starting) {
                    tmpworld.add(im.object);
                    it.add(im.object);
                    knownOjbectTypes.add(im.object.getClass().getSimpleName().toLowerCase());
                }
                continue;
            }
            String currentObjectType = mco.getClass().getSimpleName().toLowerCase();
            logger.debug("current object " + currentObjectType);
            boolean objectFirstOccurence = ! knownOjbectTypes.contains(currentObjectType);
            if (objectFirstOccurence && weights != null) {
                // temporarily set the weight to the first occurence one
                // ... if we have an estimate for the first occurence
                if (weights.firstOccurenceWeights.containsKey("i" + currentObjectType)) {
                    realizer.setExpectedDurations(
                            Map.of("i" + currentObjectType, weights.firstOccurenceWeights.get("i" + currentObjectType)),
                            false);
                }
            }
            var tree = realizer.generateReferringExpressionTree(tmpworld, mco, it, Orientation.XMINUS);
            if (printInstructions) {
                System.out.println(tree);
                System.out.println(realizer.treeToReferringExpression(tree) + " (" + -realizer.getWeightForTree(tree) + ")");
            }
            if (tree == null) {
                logger.warn("tree is null in the following context: ");
                logger.warn("current target: " + mco);
                logger.warn("current world: " + toJson(tmpworld));
                logger.warn("it: " + toJson(it));
            }
            totalCost -= realizer.getWeightForTree(tree);
            tmpworld.add(mco);
            tmpworld.addAll(mco.getBlocks());
            it.clear();
            it.add(mco);
            if (objectFirstOccurence) {
                knownOjbectTypes.add(currentObjectType);
                // reset weights
                if (weights != null && weights.weights.containsKey("i" + currentObjectType)) {
                    realizer.setExpectedDurations(
                            Map.of("i" + currentObjectType, weights.weights.get("i" + currentObjectType)),
                            false);
                }
            }

            /* TODO: In a real world we would also have the last block as "it", but we don't know which it is.
               Add a random block from getBlocks?
             */
        }
        return totalCost;
    }

    @Override
    public synchronized void handleBlockPlaced(BlockPlacedMessage request) {
        // only perform one computation at a time.
        synchronized (this) {
            long currentTime = java.lang.System.currentTimeMillis();
            checkTimeOut();
            lastUpdate.set(currentTime);
            int x = request.getX();
            int y = request.getY();
            int z = request.getZ();
            var blockPlaced = new Block(x, y, z);
            world.add(blockPlaced);
            if (plan.isEmpty()) {
                sendMessage("you are done, no more changes needed!");
                return;
            }
            /*
              Three cases:
              - block is correct and last of curr object
              - block is somewhere in curr object
              - block is incorrect
             */
            // current object is complete
            // ID for Stone Bricks
            String correctBlockType = "STONE_BRICKS";
            if (currentInstructionBlocksLeft.size() == 1 && currentInstructionBlocksLeft.contains(blockPlaced)) {
                numCorrectBlocks += 1;
                world.add(plan.get(0));
                alreadyPlacedBlocks.add(blockPlaced);
                //send message to protect this block since it is correct and change type to ID stored in correctBlockType
                sendControlMessage(blockPlaced.xpos, blockPlaced.ypos, blockPlaced.zpos, correctBlockType);
                // we can refer to the HLO and the block as it.
                // remove all blocks because we add a new block
                // remove all objects of the type we just finished because we add that one.
                it.removeIf((elem) -> elem instanceof Block || elem.getClass().equals(plan.get(0).getClass()));
                it.add(blockPlaced);
                it.add(plan.get(0));
                plan.remove(0);
                updateInstructions();
                if (plan.isEmpty()) {
                    sendMessage("Congratulations, you are done building a " + scenario,
                            NewGameState.SuccessfullyFinished);
                    checkTimeOut();
                }
                return;
            } // end current objective is complete
            if (currentInstructionBlocksLeft.contains(blockPlaced)) {
                // second case:
                // correct block, but objective not complete
                // Just note and do nothing for now
                numCorrectBlocks += 1;
                currentInstructionBlocksLeft.remove(blockPlaced);
                alreadyPlacedBlocks.add(blockPlaced);
                sendControlMessage(blockPlaced.xpos, blockPlaced.ypos, blockPlaced.zpos, correctBlockType);
                // We are in the middle of an ongoing instruction.
                // Therefore, we still use the reference frame from the
                // start of the interaction and do not update "it".
                // it = Set.of(blockPlaced);
            } else {
                // third case: incorrect block
                incorrectlyPlacedBlocks.add(blockPlaced);
                alreadyPlacedBlocks.add(blockPlaced);
                sendMessage("Not there! please remove that block again and " + currentInstruction.instruction);
                it.removeIf((elem) -> elem instanceof Block);
                it.add(blockPlaced);
                // recompute instruction with current block as "it"
                currentInstruction = computeNextInstructions().get(0);
                currentInstruction.isNewInstruction = false;
            }
            numBlocksPlaced.incrementAndGet();
        }
    }

    private void sendMessageSpaced(String message){
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage(message);
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
    }

    private void sendMessages(List<InstructionTuple> responses) {
        sendMessages(responses, true);
    }

    private void sendMessages(List<InstructionTuple> responses, boolean sendGreat) {
        boolean isFirst = true;
        for (var response: responses) {
            if (isFirst) {
                if (sendGreat && ! response.instruction.startsWith("Great")) {
                    response = new InstructionTuple( "Great! now " + response.instruction,
                            response.tree,
                            response.isNewInstruction);
                }
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            sendMessageSpaced(response.toJson());
            isFirst = false;
        }
    }

    /**
     * Computes all instructions we now have to send,
     * sends them to the player and sets currentInstruction
     * to the last of these instructions.
     */
    private void updateInstructions() {
        var newInstructions = computeNextInstructions();
        sendMessages(newInstructions);
    }

    /**
     * Computes all instructions that need to be instructed now.
     * Removes all elements from the plan that are completeled without
     * the need for user interaction (e.g. introduction objects)
     * or objects we wanted to instruct but which were already built by
     * the user.
     * @return A list of instructions that should be sent to the user
     */
    private List<InstructionTuple> computeNextInstructions() {
        if (plan.isEmpty()) {
            return new ArrayList<>(); // nothing left to instruct
        }
        var toInstruct = plan.get(0);
        // iterate over all introduction messages we might need
        if (toInstruct instanceof IntroductionMessage) {
            plan.remove(0);
            IntroductionMessage obj = (IntroductionMessage) toInstruct;
            log(obj.asJson(), "CurrentObject");
            //String message = generateResponse(world, obj.object, it, lastOrientation);
            if (obj.starting) {
                var result = computeNextInstructions();
                if (!result.isEmpty()) { // only instruct if there is something to instruct
                    result.add(0, new InstructionTuple("Now I will teach you how to build a " + obj.name, null, true));
                }
                return result;
            } else {
                world.add(obj.object);
                it.removeIf((elem) -> obj.object.getClass().equals(elem.getClass()));
                it.add(obj.object);
                var result = new ArrayList<InstructionTuple>();
                result.add(new InstructionTuple("Great! You finished building a " + obj.name, null, true));
                result.addAll(computeNextInstructions());
                return result;
            }
        }
        // Other case: not an introduction
        log(toJson(world),
                "CurrentWorld");
        log(toInstruct.asJson(), "CurrentObject");
        log(toJson(it), "it");
        currentInstructionBlocksLeft = toInstruct.getBlocks();
        // All blocks that were incorrectly placed before
        // and are part ot the thing we want to instruct are now not
        // incorrect anymore.  Otherwise, removing such a block
        // later on would not be seen as incorrect.
        incorrectlyPlacedBlocks.removeAll(currentInstructionBlocksLeft);
        currentInstructionBlocksLeft.removeAll(alreadyPlacedBlocks);
        String currentObjectsLeft = currentInstructionBlocksLeft.
                stream().
                map(MinecraftObject::asJson)
                .collect(Collectors.joining(",\n"));
        log(currentObjectsLeft, "BlocksCurrentObjectLeft");
        if (currentInstructionBlocksLeft.isEmpty()) {
            // can happen e.g. if we want to instruct to place a block
            // that was incorrectly placed before already
            plan.remove(0);
            return computeNextInstructions();
        }
        var t = System.currentTimeMillis();
        var currentTree = realizer.generateReferringExpressionTree(world, toInstruct, it, lastOrientation);

        if (currentTree == null) {
            // We somehow fail.
            // log the problem and hide from the user.
            logInstructionGenerationFailure();
            var result = new ArrayList<InstructionTuple>();
            currentInstruction = new InstructionTuple("I could not create an instruction for you, please do what you think is right", null, true);
            result.add(currentInstruction);
            return result;
        }

        log(String.valueOf(System.currentTimeMillis() - t), "RealizerTiming");
        currentInstruction = new InstructionTuple(
                toInstruct.getVerb() + " " + realizer.treeToReferringExpression(currentTree),
                currentTree, 
                true
        );

        var result = new ArrayList<InstructionTuple>();
        result.add(currentInstruction);
        return result;
    }

    /**
     * Checks whether the secret word for payment should be shown and if so starts a thread for that.
     */
    private synchronized void checkTimeOut() {
        if (SecretWordThreadStarted || !config.getShowSecret()) {
            return;
        }
        boolean timePassed = System.currentTimeMillis() - startTime >= config.getTimeoutMinutes()*60*1000;
        if (plan.isEmpty() || (numCorrectBlocks >= config.getTimeoutMinBlocks()  && timePassed)) {
            SecretWordThreadStarted = true;
            new Thread(() -> {
                while (true) {
                    logger.info("timeout reached: " + System.currentTimeMillis() + " start: "+startTime);
                    if (this.playerHasLeft) {
                        // no player anymore, stop trying to send messages to them
                        break;
                    }
                    sendMessage("Thank you for participating in our experiment. The secret word is: "
                            + config.getSecretWord());
                    try {
                        Thread.sleep(30 * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    @Override
    public synchronized void handleBlockDestroyed(BlockDestroyedMessage request) {
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        var block = new Block(x, y, z);
        world.remove(block);
        // We instructed the user to remove the block,
        // so simply remove it from our list.
        if (incorrectlyPlacedBlocks.contains(block)) {
            it.removeIf((elem) -> elem instanceof Block);
            alreadyPlacedBlocks.remove(block);
            // We are in this situation: We gave an instruction to the user,
            // the user placed a block incorrectly, now corrected that error.
            // Therefore, computing the next instructions should give us exactly
            // one instruction, namely the one we tried before misplacing the block.
            var newInstructions = computeNextInstructions();
            if (newInstructions.isEmpty()) {
                logInstructionGenerationFailure();
                // no clue what else to do here.
                return;
            }
            if (newInstructions.size() > 1) {
                log("got " + newInstructions.size() + " instructions", "handleBlocksDestroyed");
                logInstructionGenerationFailure();
                return;
            }
            var instruction = newInstructions.get(0);
            // we newly generate it, but it is still the same objective as before.
            instruction.isNewInstruction = false;
            sendMessages(List.of(instruction));
            return;
        }
        if (plan.contains(block)) {
            // we already have this block in our plan, no need to add it.
            return;
        }
        
        // If a block that should be placed is removed again, re-add it to the plan
        // and instruct the user to place this block again
        if (alreadyPlacedBlocks.contains(block)) {
            // We cannot say "previous block" when the last action was a removal
            it.removeIf((elem) -> elem instanceof Block);
            alreadyPlacedBlocks.remove(block);
            plan.add(0, block);
            var newInstructions = computeNextInstructions();
            var currI = newInstructions.get(0);
            currI.isNewInstruction = false;
            sendMessage("Please add this block again.");
            sendMessage(currI.toJson());
            lastUpdate.set(java.lang.System.currentTimeMillis());
        } else {
            // Just ignore the vandalism to preexisting blocks.
        }
    }

    @Override
    public synchronized void handleStatusInformation(StatusMessage request) {
        if (objectiveSet.getCount() > 0) {
            // only start recording status information after
            // the first objective was actually computed.
            return;
        }
        var currNumBlocksPlaced = numBlocksPlaced.get();
        synchronized (this) {
            if (currNumBlocksPlaced < numBlocksPlaced.get()) {
                // instructions for a new object were planned after we started waiting,
                // our information is outdated, so skip this one
                return;
            }
            logger.debug("handleStatusInformation " + request);
            if(lastUpdate.get() + MESSAGE_PAUSE > java.lang.System.currentTimeMillis()){
                return;
            }
            var x = request.getXDirection();
            var z = request.getZDirection();
            Orientation newOrientation;
            if (abs(x) > abs(z)) {
                // User looks along X-axis
                if (x > 0) {
                    newOrientation = Orientation.XPLUS;
                } else {
                    newOrientation = Orientation.XMINUS;
                }
            } else {
                // looking along Z axis
                if (z > 0) {
                    newOrientation = Orientation.ZPLUS;
                } else {
                    newOrientation = Orientation.ZMINUS;
                }
            }

            boolean orientationStayed = newOrientation == lastOrientation;
            lastOrientation = newOrientation;

            // only re-send the current instruction after five seconds.
            if (orientationStayed && lastUpdate.get() + RESEND_INTERVAL > java.lang.System.currentTimeMillis()) {
                return;
            }
            if (orientationStayed) {
                // we still send the instruction, but mark that this is now a repetition.
                currentInstruction.isNewInstruction = false;
            } else {
                log(newOrientation.toString(), "NewOrientation");
                var t = System.currentTimeMillis();
                var newInstruction = realizer.generateReferringExpressionTree(world, plan.get(0), it, lastOrientation);
                log(String.valueOf(System.currentTimeMillis() - t), "RealizerTiming");
                if (currentInstruction.tree.equals(newInstruction)
                        && lastUpdate.get() + RESEND_INTERVAL > java.lang.System.currentTimeMillis()) {
                    // we turned but five seconds are not over and the turning did not
                    // change the instruction, so no need to send again.
                    return;
                }
                if (newInstruction != null) {
                    String instrStr = plan.get(0).getVerb() + " " + realizer.treeToReferringExpression(newInstruction);
                    currentInstruction = new InstructionTuple(instrStr, newInstruction, false);
                } else {
                    // We still want to say the same thing but somehow fail.
                    // log the problem and hide from the user.
                    logInstructionGenerationFailure();
                    return;
                }
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            sendMessageSpaced(currentInstruction.toJson());
        }
    }

    /**
     * logs the current world state as NLGFailure.
     */
    private void logInstructionGenerationFailure() {
        logger.warn("Failed to build instruction");
        log("{\"world\":" + toJson(world)
                        + ", \"target\": " + plan.get(0).asJson()
                        + ", \"it\": " + toJson(it)
                        + ", \"orientation\": \"" + lastOrientation + "\""
                , "NLGFailure");
    }

    @Override
    protected void playerLeft() {
        // AFAIK, there is no cleanup we need to do.
    }

    private static String toJson(Collection<MinecraftObject> c) {
        return "["
                + c.stream()
                .map(MinecraftObject::asJson)
                .collect(Collectors.joining(", "))
                + "]";
    }

    public MinecraftRealizer getRealizer() {
        return realizer;
    }

    @Override
    public String getArchitectInformation() {
        return config.getName();
    }
}
