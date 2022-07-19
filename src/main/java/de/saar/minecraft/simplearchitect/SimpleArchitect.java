package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.WildcardBlock;
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
import org.tinylog.Logger;
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
        /**
         * marks whether we instruct the object for the first time.
         */
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

    private static final int RESEND_INTERVAL = 11000;
    private static final int MESSAGE_PAUSE = 500;

    protected PlanCreator planCreator;

    protected HashSet<MinecraftObject> it = new HashSet<>();
    protected List<MinecraftObject> plan;
    private Set<Block> currentInstructionBlocksLeft = Set.of();
    private Map<String, Integer> typeAmounts = new HashMap<>();
    private Map<String, Block> uniqueBlocks = new HashMap<>();
    protected Set<MinecraftObject> world;
    protected MinecraftRealizer realizer;
    protected AtomicInteger numBlocksPlaced = new AtomicInteger(0);
    protected AtomicLong lastUpdate = new AtomicLong(0);
    protected InstructionTuple currentInstruction;
    protected Orientation lastOrientation = Orientation.XMINUS;
    protected String scenario;
    protected final CountDownLatch readyCounter = new CountDownLatch(1);
    private final CountDownLatch objectiveSet = new CountDownLatch(1);
    private long startTime;
    private boolean SecretWordThreadStarted = false;
    private int numCorrectBlocks = 0;
    private int buildBlockType = 1; // stone
    public WeightEstimator.WeightResult weights;

    protected final SimpleArchitectConfiguration config;

    /**
     * These are all blocks that exist in the world (except water block)
     */
    protected Set<Block> alreadyPlacedBlocks = new HashSet<>();
    /**
     * These are blocks that were placed but we did not instruct them (yet)
     * They might be part of what we want to instruct later on though.
     */
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

    private void addPlacedBlock(Block block) {
        alreadyPlacedBlocks.add(block);
        
        String type = block.getType();
        Logger.debug("Placed {} block in architect world.", type);
        if (!typeAmounts.containsKey(type)) {
            typeAmounts.put(type, 0);
        }
        if (typeAmounts.get(type) == 0) {
            Logger.debug("Determined unique {} block in architect world.", type);
            uniqueBlocks.put(type, block);
            block.setUnique();
        } else if (typeAmounts.get(type) == 1) {
            Logger.debug("There is no longer a unique {} block in architect world.", type);
            uniqueBlocks.get(type).setNotUnique();
            uniqueBlocks.remove(type);
        }
        typeAmounts.put(type, typeAmounts.get(type) + 1);
    }

    private void removePlacedBlock(Block block) {
        alreadyPlacedBlocks.remove(block);

        String type = block.getType();
        if (!typeAmounts.containsKey(type)) {
            typeAmounts.put(type, 0);
        }
        if (typeAmounts.get(type) == 2) {
            //TODO: there would be a better implementation if we store all blocks by type, but this shouldn't be triggered often
            Block typedBlock = null;
            for (Block placedBlock : alreadyPlacedBlocks) {
                if (placedBlock.getType().equals(type)) {
                    typedBlock = placedBlock;
                    break;
                }
            }
            assert typedBlock != null;
            uniqueBlocks.put(type, typedBlock);
            Logger.debug("Determined unique {} block in architect world.", type);
            typedBlock.setUnique();
        } else if (typeAmounts.get(type) == 1) {
            Logger.debug("There is no longer a unique {} block in architect world.", type);
            uniqueBlocks.get(type).setNotUnique();
            uniqueBlocks.remove(type);
        }
        typeAmounts.put(type, typeAmounts.get(type) - 1);
    }

    @Override
    public synchronized void playerReady() {
        startTime = java.lang.System.currentTimeMillis();
        Logger.debug("received playerReady");
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
        sendMessagesInitial(instructions, false);
        lastUpdate.set(java.lang.System.currentTimeMillis());
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
        Logger.debug("Initializing with {}", message);
        setGameId(message.getGameId());
        scenario = message.getName();
        String instructionlevel = config.getInstructionlevel();
        if (!config.getPlanFile().isEmpty()) {
            planCreator = new PlanCreatorFromFile(scenario,
                    config.getPlanFile());
        } else {
            planCreator = new PlanCreator(scenario, CostFunction.InstructionLevel.valueOf(instructionlevel));
        }
        this.plan = planCreator.getPlan();
        this.world = planCreator.getInitialWorld();
        for (Block block : planCreator.getBlocksCurrentWorld()) {
            addPlacedBlock(block);
        }
        Logger.debug("initialization done");
        readyCounter.countDown();
    }

    protected List<List<Tree<String>>> generateSeedInstructionTrees() {
        List<List<Tree<String>>> result = new ArrayList<>();
        for (String currScenario : List.of("house", "bridge")) {
            for (var il : CostFunction.InstructionLevel.values()) {
                Logger.debug("trying instruction level {}", il);
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
        for (var mco : tmpplan) {
            if (mco instanceof IntroductionMessage im) {
                if (!im.starting) {
                    tmpworld.add(im.object);
                    it.add(im.object);
                    knownOjbectTypes.add(((IntroductionMessage) mco).object.getClass().getSimpleName().toLowerCase());
                }
                continue;
            }
            String currentObjectType = mco.getClass().getSimpleName().toLowerCase();
            boolean objectFirstOccurence = !knownOjbectTypes.contains(currentObjectType);
            var tree = realizer.generateReferringExpressionTree(tmpworld, mco, it, Orientation.ZMINUS);
            if (tree != null) {
                result.add(tree);
            } else {
                Logger.warn("tree is null for object {}", mco);
                Logger.warn("world: {}", toJson(tmpworld));
                Logger.warn("it: {}", toJson(it));
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
            String type = request.getType().toLowerCase(); 
            String replType = request.getReplType().toLowerCase(); 

            var blockPlaced = new Block(x, y, z, type); // the block the user placed, tracked by the plan generation
            var blockGenerated = new Block(x, y, z, replType); // the replacement block (for a correct block), that will be part of the actual world
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
            if (currentInstructionBlocksLeft.size() == 1 && currentInstructionBlocksLeft.contains(blockPlaced)) {
                world.add(blockGenerated);
                numCorrectBlocks += 1;
                world.add(plan.get(0));
                addPlacedBlock(blockGenerated);
                //send message to protect this block since it is correct
                sendControlMessage(blockPlaced.xpos, blockPlaced.ypos, blockPlaced.zpos, blockPlaced.getType());
                // we can refer to the HLO and the block as it.
                // remove all blocks because we add a new block
                // remove all objects of the type we just finished because we add that one.
                it.removeIf((elem) -> elem instanceof Block || elem.getClass().equals(plan.get(0).getClass()));
                it.add(blockPlaced); //TODO: this looks fishy
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
                world.add(blockGenerated);
                // second case:
                // correct block, but objective not complete
                // Just note and do nothing for now
                numCorrectBlocks += 1;
                currentInstructionBlocksLeft.remove(blockPlaced);
                addPlacedBlock(blockGenerated);
                sendControlMessage(blockPlaced.xpos, blockPlaced.ypos, blockPlaced.zpos, blockPlaced.getType());
                // We are in the middle of an ongoing instruction.
                // Therefore, we still use the reference frame from the
                // start of the interaction and do not update "it".
                // it = Set.of(blockPlaced);
            } else {
                world.add(blockPlaced);
                // third case: incorrect block
                incorrectlyPlacedBlocks.add(blockPlaced);
                addPlacedBlock(blockPlaced);
                lastUpdate.set(java.lang.System.currentTimeMillis());
                sendMessageSpaces();
                if (currentInstructionBlocksLeft.contains(new WildcardBlock(blockPlaced.xpos, blockPlaced.ypos, blockPlaced.zpos))) {
                    sendMessage("Wrong block type! please remove that block again and " + currentInstruction.instruction);
                } else {
                    sendMessage("Not there! please remove that block again and " + currentInstruction.instruction);

                }
                sendMessageSpaces();
                it.removeIf((elem) -> elem instanceof Block);
                it.add(blockPlaced);
                // recompute instruction with current block as "it"
                currentInstruction = computeNextInstructions().get(0);
                currentInstruction.isNewInstruction = false;
            }
            numBlocksPlaced.incrementAndGet();
        }
    }

    private void sendMessageSpaces() {
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
        sendMessage("|");
    }

    private void sendMessagesInitial(List<InstructionTuple> responses, boolean sendGreat) {
        boolean isFirst = true;
        for (var response : responses) {
            if (isFirst) {
                if (sendGreat && !response.instruction.startsWith("Great")) {
                    response = new InstructionTuple("Great! now " + response.instruction,
                            response.tree,
                            response.isNewInstruction);
                }
            } else {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            sendMessage(response.toJson());
            isFirst = false;
        }
    }

    private void sendMessages(List<InstructionTuple> responses) {
        sendMessages(responses, true);
    }

    private void sendMessages(List<InstructionTuple> responses, boolean sendGreat) {
        boolean isFirst = true;
        for (var response : responses) {
            if (isFirst) {
                sendMessageSpaces();
                if (sendGreat && !response.instruction.startsWith("Great")) {
                    response = new InstructionTuple("Great! now " + response.instruction,
                            response.tree,
                            response.isNewInstruction);
                }
            } else {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            sendMessage(response.toJson());
            isFirst = false;
        }
        sendMessageSpaces();
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
     *
     * @return A list of instructions that should be sent to the user
     */
    private List<InstructionTuple> computeNextInstructions() {
        if (plan.isEmpty()) {
            return new ArrayList<>(); // nothing left to instruct
        }
        var toInstruct = plan.get(0);
        // iterate over all introduction messages we might need
        if (toInstruct instanceof IntroductionMessage obj) {
            plan.remove(0);
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
        boolean timePassed = System.currentTimeMillis() - startTime >= (long) config.getTimeoutMinutes() * 60 * 1000;
        if (plan.isEmpty() || (numCorrectBlocks >= config.getTimeoutMinBlocks() && timePassed)) {
            SecretWordThreadStarted = true;
            new Thread(() -> {
                while (true) {
                    Logger.info("timeout reached: {} start: {}", System.currentTimeMillis(),  startTime);
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
        var block = new WildcardBlock(x, y, z);
        world.remove(block);
        // We instructed the user to remove the block,
        // so simply remove it from our list.
        if (incorrectlyPlacedBlocks.contains(block)) {
            it.removeIf((elem) -> elem instanceof Block);
            removePlacedBlock(block);
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
        //should never happen with block protection
        if (alreadyPlacedBlocks.contains(block)) {
            // We cannot say "previous block" when the last action was a removal
            it.removeIf((elem) -> elem instanceof Block);
            world.add(block);
            //need to rmeove this with block protection, otherwise strange race conditions can appear
            //that suggest that no block is there in our system while a protected block is present
            /*alreadyPlacedBlocks.remove(block);
            plan.add(0, block);
            var newInstructions = computeNextInstructions();
            var currI = newInstructions.get(0);
            currI.isNewInstruction = false;
            sendMessage("Please add this block again.");
            sendMessage(currI.toJson());
            lastUpdate.set(java.lang.System.currentTimeMillis());*/
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
            Logger.debug("handleStatusInformation {}", request);
            if (lastUpdate.get() + MESSAGE_PAUSE > java.lang.System.currentTimeMillis()) {
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
            sendMessageSpaces();
            sendMessage(currentInstruction.toJson());
            sendMessageSpaces();
        }
    }

    /**
     * logs the current world state as NLGFailure.
     */
    private void logInstructionGenerationFailure() {
        Logger.warn("Failed to build instruction");
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

    protected static String toJson(Collection<MinecraftObject> c) {
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
