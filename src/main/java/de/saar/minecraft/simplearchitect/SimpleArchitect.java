package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

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

    protected PlanCreator planCreator;
    
    protected Set<MinecraftObject> it = Set.of();
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

    private final SimpleArchitectConfiguration config;

    /** These are all blocks that exist in the world (except water block)*/
    protected Set<Block> alreadyPlacedBlocks = new HashSet<>();
    /** These are blocks that were placed but we did not instruct them (yet)
    They might be part of what we want to instruct later on though.*/
    protected Set<Block> incorrectlyPlacedBlocks = new HashSet<>();

    public SimpleArchitect(SimpleArchitectConfiguration config) {
        this.config = config;
        this.realizer = MinecraftRealizer.createRealizer();
        switch (config.getWeightSource()) {
            case "random":
                this.realizer.randomizeExpectedDurations();
                break;
            case "bootstrapped":
                var estimator = new WeightEstimator(config.getWeightTrainingDatabase(),
                        config.getWeightTrainingDBUser(),
                        config.getWeightTrainingDBPassword(),
                        config.getTrainingSamplingLowerPercentile(),
                        config.getTrainingSamplingUpperPercentile());
                var weights = estimator.sampleDurationCoeffsWithBootstrap(config.getTrainingNumBootstrapRuns());
                realizer.setExpectedDurations(weights, false);
                break;
            case "optimal":
                var est = new WeightEstimator(config.getWeightTrainingDatabase(),
                        config.getWeightTrainingDBUser(),
                        config.getWeightTrainingDBPassword(),
                        config.getTrainingSamplingLowerPercentile(),
                        config.getTrainingSamplingUpperPercentile());
                realizer.setExpectedDurations(est.predictDurationCoeffsFromAllGames(), false);
                break;
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
        var weights = this.realizer.getWeightsAsJson();
        log(weights, "GrammarWeights");
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
        if (instructionlevel.equals("adaptive")) {
            planCreator = getOptimalPlan(scenario);
        } else {
            planCreator = new PlanCreator(scenario, CostFunction.InstructionLevel.valueOf(instructionlevel));
        }
        this.plan = planCreator.getPlan();
        this.world = planCreator.getInitialWorld();
        this.alreadyPlacedBlocks = planCreator.getBlocksCurrentWorld();
        logger.debug("initialization done");
        readyCounter.countDown();
    }

    protected PlanCreator getOptimalPlan(String scenario) {
        PlanCreator argmin = null;
        double min = Double.POSITIVE_INFINITY;
        for (var il : CostFunction.InstructionLevel.values()) {
            logger.warn("trying instruction level " +  il);
            var planCreator = new PlanCreator(scenario, il);
            double cost = getCostForPlanCreator(planCreator);
            logger.warn("cost: " + cost);
            if (cost < min) {
                argmin = planCreator;
            }
        }
        return argmin;
    }
    
    protected double getCostForPlanCreator(PlanCreator planCreator) {
        var tmpplan = planCreator.getPlan();
        var tmpworld = planCreator.getInitialWorld();
        double totalCost = 0;
        Set<MinecraftObject> it = new HashSet<>();
        for (var mco: tmpplan) {
            if (mco instanceof IntroductionMessage) {
                continue;
            }
            var tree = realizer.generateReferringExpressionTree(tmpworld, mco, it, Orientation.ZMINUS);
            totalCost += realizer.getWeightForTree(tree);
            tmpworld.add(mco);
            tmpworld.addAll(mco.getBlocks());
            it.clear();
            it.add(mco);
            // TODO: In a real world we would also have the last block as "it", but we don't know which it
            // is. Add a random block from getBlocks?
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
            if (currentInstructionBlocksLeft.size() == 1 && currentInstructionBlocksLeft.contains(blockPlaced)) {
                numCorrectBlocks += 1;
                world.add(plan.get(0));
                alreadyPlacedBlocks.add(blockPlaced);
                // we can refer to the HLO and the block as it.
                if (!blockPlaced.equals(plan.get(0))) {
                    it = Set.of(blockPlaced, plan.get(0));
                } else {
                    // Set.of cannot deal with multiple elements being the same,
                    // therefore we check this in this if/else clause.
                    // Both are the same if we instructed to build a single block.
                    it = Set.of(blockPlaced);
                }
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
                // We are in the middle of an ongoing instruction.
                // Therefore, we still use the reference frame from the
                // start of the interaction and do not update "it".
                // it = Set.of(blockPlaced);
            } else {
                // third case: incorrect block
                incorrectlyPlacedBlocks.add(blockPlaced);
                alreadyPlacedBlocks.add(blockPlaced);
                sendMessage("Not there! please remove that block again and " + currentInstruction.instruction);
            }
            numBlocksPlaced.incrementAndGet();
        }
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
            sendMessage(response.toJson());
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
                var newit = new HashSet<>(it);
                newit.add(obj.object);
                it = newit;
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
            it = Set.of();
            alreadyPlacedBlocks.remove(block);
            return;
        }
        // If a block that should be placed is removed again, re-add it to the plan
        // and instruct the user to place this block again
        if (alreadyPlacedBlocks.contains(block)) {
            it = Set.of(); // We cannot say "previous block" when the last action was a removal
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
                    logger.warn("Failed to build instruction");
                    log("{\"world\":" + toJson(world)
                                    + ", \"target\": " + plan.get(0).asJson()
                                    + ", \"it\": " + toJson(it)
                                    + ", \"orientation\": \"" + newOrientation + "\""
                            , "NLGFailure");
                    return;
                }
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            sendMessage(currentInstruction.toJson());
        }
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

    @Override
    public String getArchitectInformation() {
        return "SimpleArchitect-" + config.getInstructionlevel();
    }
}
