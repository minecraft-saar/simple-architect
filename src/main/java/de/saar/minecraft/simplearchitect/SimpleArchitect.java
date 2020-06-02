package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.Floor;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Railing;
import de.saar.coli.minecraft.relationextractor.Relation.Orientation;
import de.saar.coli.minecraft.relationextractor.Row;
import de.saar.coli.minecraft.relationextractor.UniqueBlock;
import de.saar.coli.minecraft.relationextractor.Wall;
import de.saar.minecraft.architect.AbstractArchitect;
import de.saar.minecraft.shared.BlockDestroyedMessage;
import de.saar.minecraft.shared.BlockPlacedMessage;
import de.saar.minecraft.shared.NewGameState;
import de.saar.minecraft.shared.StatusMessage;
import de.saar.minecraft.shared.WorldSelectMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import umd.cs.shop.JSJshop;
import umd.cs.shop.JSState;
import umd.cs.shop.JSPredicateForm;
import umd.cs.shop.JSTerm;
import umd.cs.shop.JSTaskAtom;
import umd.cs.shop.costs.CostFunction;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private static final CostFunction.InstructionLevel instructionlevel = CostFunction.InstructionLevel.valueOf(
            System.getProperty("instructionlevel", "BLOCK"));

    private static final Logger logger = LogManager.getLogger(SimpleArchitect.class);
    protected Set<MinecraftObject> it = Set.of();
    private List<MinecraftObject> plan;
    private Set<Block> currentInstructionBlocksLeft = Set.of();
    protected Set<MinecraftObject> world;
    private MinecraftRealizer realizer;
    protected AtomicInteger numBlocksPlaced = new AtomicInteger(0);
    protected AtomicLong lastUpdate = new AtomicLong(0);
    protected String currentInstruction;
    protected Orientation lastOrientation = Orientation.ZPLUS;
    protected String scenario;
    private final CountDownLatch readyCounter = new CountDownLatch(1);
    private final CountDownLatch objectiveSet = new CountDownLatch(1);
    private long startTime;
    private boolean SecretWordThreadStarted = false;
    private int numCorrectBlocks = 0;

    private SimpleArchitectConfiguration config;

    /** These are all blocks that exist in the world (except water block)*/
    protected Set<Block> alreadyPlacedBlocks = new HashSet<>();
    /** These are blocks that were placed but we did not instruct them (yet)
    They might be part of what we want to instruct later on though.*/
    protected Set<Block> incorrectlyPlacedBlocks = new HashSet<>();

    public SimpleArchitect(SimpleArchitectConfiguration config) {
        this.config = config;
    }

    @Override
    public void playerReady() {
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
        log(planString, "InitialPlan");
        var instructions = computeNextInstructions();
        sendMessages(instructions, true);
        objectiveSet.countDown();
    }

    private static InputStream getResourceStream(String resName) {
        return SimpleArchitect.class.getResourceAsStream(resName);
    }

    private static String getResourceAsString(String resName) {
        return new BufferedReader(new InputStreamReader(getResourceStream(resName)))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    private String createPlan(JSJshop planner, String scenario) {
        logger.debug("creating plan for " + scenario);
        int mctsruns = 1; //number of runs the planner tries to do
        int timeout = 10000; //time the planner runs in ms

        var initialworld = getResourceStream("/de/saar/minecraft/worlds/" + scenario + ".csv");
        var domain = getResourceStream("/de/saar/minecraft/domains/" + scenario + ".lisp");
        String problem = getResourceAsString("/de/saar/minecraft/domains/" + scenario + ".init").strip();
        planner.nlgSearch(mctsruns, timeout, initialworld, problem, domain, instructionlevel);
        String precomputedFileName = scenario + "-" + instructionlevel.name().toLowerCase() + ".plan";
        return getResourceAsString("/de/saar/minecraft/domains/" + precomputedFileName);
    }

    @Override
    public void initialize(WorldSelectMessage message) {
        logger.debug(() -> "initializing with " + message);
        setGameId(message.getGameId());
        scenario = message.getName();
        this.plan = computePlan(scenario);
        logger.debug("initialization done");
        readyCounter.countDown();
    }

    /**
     * computes the plan and initializes the world.
     */
    public List<MinecraftObject> computePlan(String scenario) {
        logger.debug("computing plan");
        JSJshop planner = new JSJshop();
        var jshopPlan = createPlan(planner, scenario);
        this.realizer = MinecraftRealizer.createRealizer();
        world = transformState(planner.prob.state());
        // note which blocks already exist in the world.
        world.forEach((x) ->
                x.getBlocks().forEach((block) ->
                        alreadyPlacedBlocks.add(new Block(block.xpos, block.ypos, block.zpos))
                )
        );
        logger.debug("plan computed");
        return transformPlan(jshopPlan);
    }

    public MinecraftObject createRailing(String[] taskArray) {
        int x1, x2, y1, z1, z2, length, dir;
        x1 = (int) Double.parseDouble(taskArray[1]);
        y1 = (int) Double.parseDouble(taskArray[2]);
        z1 = (int) Double.parseDouble(taskArray[3]);
        length = (int) Double.parseDouble(taskArray[4]);
        dir = (int) Double.parseDouble(taskArray[5]);
        //east=1=>x+, west=2=>x-, north=3=>z-, south=4=>z+
        if (dir == 1) {
            x2 = x1 + length - 1;
            z2 = z1;
        } else if (dir == 2) {
            x2 = x1;
            x1 = x1 - length + 1;
            z2 = z1;
        } else if (dir == 3) {
            x2 = x1;
            z2 = z1;
            z1 = z1 - length + 1;
        } else { // dir == 4
            x2 = x1;
            z2 = z1 + length - 1;
        }
        return new Railing("railing", x1, z1, x2, z2, y1);
    }

    public MinecraftObject createWall(String[] taskArray) {
        int x1, x2, y1, y2, z1, z2, length, height, dir;
        x1 = (int) Double.parseDouble(taskArray[1]);
        y1 = (int) Double.parseDouble(taskArray[2]);
        z1 = (int) Double.parseDouble(taskArray[3]);
        length = (int) Double.parseDouble(taskArray[4]);
        height = (int) Double.parseDouble(taskArray[5]);
        dir = (int) Double.parseDouble(taskArray[6]);
        //east=1=>x+, west=2=>x-, north=3=>z-, south=4=>z+

        if (dir == 1) {
            x2 = x1 + length - 1;
            z2 = z1;
        } else if (dir == 2) {
            x2 = x1;
            x1 = x1 - length + 1;
            z2 = z1;
        } else if (dir == 3) {
            x2 = x1;
            z2 = z1;
            z1 = z1 - length + 1;
        } else {
            x2 = x1;
            z2 = z1 + length - 1;
        }
        y2 = y1 + height - 1;
        return new Wall("wall", x1, y1, z1, x2, y2, z2);
    }

    public MinecraftObject createFloor(String[] taskArray) {
        int x1, x2, y1, z1, z2, length, width, dir;
        x1 = (int) Double.parseDouble(taskArray[1]);
        y1 = (int) Double.parseDouble(taskArray[2]);
        z1 = (int) Double.parseDouble(taskArray[3]);
        length = (int) Double.parseDouble(taskArray[4]);
        width = (int) Double.parseDouble(taskArray[5]);
        dir = (int) Double.parseDouble(taskArray[6]);
        //east=1=>x+, west=2=>x-, north=3=>z-, south=4=>z+
        if (dir == 1) {
            x2 = x1 + width - 1;
            z2 = z1 + length - 1;
        } else if (dir == 2) {
            x2 = x1;
            x1 = x1 - width + 1;
            z2 = z1 + length - 1;
        } else if (dir == 3) {
            x2 = x1 + length - 1;
            z2 = z1;
            z1 = z1 - width + 1;
        } else {
            x2 = x1 + length - 1;
            z2 = z1 + width - 1;
        }
        return new Floor("floor", x1, z1, x2, z2, y1);

    }

    public MinecraftObject createRow(String[] taskArray){
        int x1, y1, z1, x2, z2, length, dir;
        x1 = (int) Double.parseDouble(taskArray[1]);
        y1 = (int) Double.parseDouble(taskArray[2]);
        z1 = (int) Double.parseDouble(taskArray[3]);
        length = (int) Double.parseDouble(taskArray[4]);
        dir = (int) Double.parseDouble(taskArray[5]);
        //east=1=>x+, west=2=>x-, north=3=>z-, south=4=>z+
        if (dir == 1) {
            x2 = x1 + length - 1;
            z2 = z1;
        } else if (dir == 2) {
            x2 = x1;
            x1 = x1 - length + 1;
            z2 = z1;
        } else if (dir == 3) {
            x2 = x1;
            z2 = z1;
            z1 = z1 - length + 1;
        } else {
            x2 = x1;
            z2 = z1 + length - 1;
        }
        return new Row("row", x1, z1, x2, z2, y1);
    }

    public List<MinecraftObject> transformPlan(String jshopPlan) {
        var result = new ArrayList<MinecraftObject>();
        String[] tasks = jshopPlan.split("\n");
        JSTaskAtom t;
        for (String task : tasks) {
            //t = (JSTaskAtom) jshopPlan.elementAt(i);
            //String task = tasks[i];
            //log(task, "Plan");
            String[] taskArray = task.split(" ");
            int x1, y1, z1, x2, y2, z2, length, width, height, dir;
            boolean inst = false;
            switch (taskArray[0]) {
                case "(!place-block":
                    x1 = (int) Double.parseDouble(taskArray[2]);
                    y1 = (int) Double.parseDouble(taskArray[3]);
                    z1 = (int) Double.parseDouble(taskArray[4]);
                    result.add(new Block(x1, y1, z1));
                    break;
                case "(!build-row":
                    result.add(createRow(taskArray));
                    break;
                case "(!build-row-starting":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRow(taskArray), true, "row"));
                    break;
                case "(!build-row-finished":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRow(taskArray), false, "row"));
                    break;
                case "(!build-wall-starting":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createWall(taskArray), true, "wall"));
                    break;
                case "(!build-wall-finished":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createWall(taskArray), false, "wall"));
                    break;
                case "(!build-wall":
                    result.add(createWall(taskArray));
                    break;
                case "(!build-railing-starting":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRailing(taskArray), true, "railing"));
                    break;
                case "(!build-railing-finished":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRailing(taskArray), false, "railing"));
                    break;
                case "(!build-railing":
                    result.add(createRailing(taskArray));
                    break;
                case "(!build-floor-starting":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createFloor(taskArray), true, "floor"));
                    break;
                case "(!build-floor-finished":
                    if (instructionlevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createFloor(taskArray), false, "floor"));
                    break;
                case "(!build-floor":
                    result.add(createFloor(taskArray));
                    break;
                case "(!place-block-hidden":
                    break;
                default:
                    //log(task, "NewAction");
                    System.out.println("New Action " + task);
                    break;
            }
        }
        return result;
    }

    public HashSet<MinecraftObject> transformState(JSState state) {
        HashSet<MinecraftObject> set = new HashSet();
        for (JSPredicateForm term : state.atoms()) {
            String name = (String) term.elementAt(0);
            if (!name.equals("block-at")) {
                //System.out.println("Not a block: " + term.toString());
                continue;
            }
            JSTerm data = (JSTerm) term.elementAt(1);
            String type = (String) data.elementAt(0);
            if (type.equals("water")) {
                // water behaves differently from normal blocks,
                // e.g. you can still put blocks into water blocks
                // this confuses the tracking and we therefore ignore water.
                continue;
            }
            JSTerm tmp = (JSTerm) term.elementAt(2);
            int x = (int) Double.parseDouble(tmp.toStr().toString());
            tmp = (JSTerm) term.elementAt(3);
            int y = (int) Double.parseDouble(tmp.toStr().toString());
            tmp = (JSTerm) term.elementAt(4);
            int z = (int) Double.parseDouble(tmp.toStr().toString());
            //System.out.println("Block: " + type + " " + x + " " + y + " "+ z);
            set.add(new UniqueBlock(type, x, y, z));
        }
        return set;
    }

    @Override
    public void handleBlockPlaced(BlockPlacedMessage request) {
        // only perform one computation at a time.
        synchronized (numBlocksPlaced) {
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
                sendMessage("Not there! please remove that block again and " + currentInstruction);
            }
            numBlocksPlaced.incrementAndGet();
        }
    }

    private void sendMessages(List<String> responses) {
        sendMessages(responses, true);
    }

    private void sendMessages(List<String> responses, boolean sendGreat) {
        boolean isFirst = true;
        for (var response: responses) {
            if (isFirst) {
                if (sendGreat && ! response.startsWith("Great")) {
                    response = "Great! now " + response;
                }
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            sendMessage(response);
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
    private List<String> computeNextInstructions() {
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
                    result.add(0, "Now I will teach you how to build a " + obj.name);
                }
                return result;
            } else {
                world.add(obj.object);
                var newit = new HashSet<>(it);
                newit.add(obj.object);
                it = newit;
                var result = new ArrayList<String>();
                result.add( "Great! You finished building a " + obj.name);
                result.addAll(computeNextInstructions());
                return result;
            }
        }
        // Other case: not an introduction
        log(toJson(world),
                "CurrentWorld");
        log(toInstruct.asJson(), "CurrentObject");
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
        currentInstruction = generateResponse(world, toInstruct, it, lastOrientation);
        var result = new ArrayList<String>();
        result.add(currentInstruction);
        return result;
    }

    /**
     * Checks whether the secret word for payment should be shown and if so starts a thread for that.
     */
    private void checkTimeOut() {
        if (SecretWordThreadStarted || !config.getShowSecret()) {
            return;
        }
        boolean timePassed = System.currentTimeMillis() - startTime >= config.getTimeoutMinutes()*60*1000; // 10 minutes
        if (plan.isEmpty() || numCorrectBlocks >= config.getTimeoutMinBlocks()  && timePassed) {
            SecretWordThreadStarted = true;
            new Thread(() -> {
                while (true) {
                    logger.info("timeout reached: " + System.currentTimeMillis() + " start: "+startTime);
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
    public void handleBlockDestroyed(BlockDestroyedMessage request) {
        // TODO add logic to only say this if the previous block was correct
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        var block = new Block(x, y, z);
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
            sendMessage(newInstructions.get(0));
            sendMessage("Please add this block again.");
            lastUpdate.set(java.lang.System.currentTimeMillis());
        } else {
            // Just ignore the vandalism to preexisting blocks.
        }
    }

    @Override
    public void handleStatusInformation(StatusMessage request) {
        if (objectiveSet.getCount() > 0) {
            // only start recording status information after
            // the first objective was actually computed.
            return;
        }
        var currNumBlocksPlaced = numBlocksPlaced.get();
        synchronized (numBlocksPlaced) {
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
            if (orientationStayed && lastUpdate.get() + 5000 > java.lang.System.currentTimeMillis()) {
                return;
            }
            if (!orientationStayed) {
                log(newOrientation.toString(), "NewOrientation");
                var newInstruction = generateResponse(world, plan.get(0), it, lastOrientation);
                if (newInstruction.equals(currentInstruction)
                        && lastUpdate.get() + 5000 > java.lang.System.currentTimeMillis()) {
                    // we turned but five seconds are not over and the turning did not
                    // change the instruction, so no need to send again.
                    return;
                }
                if (!newInstruction.contains("*NONE*")) {
                    currentInstruction = newInstruction;
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
            sendMessage(currentInstruction);
        }
    }

    private String toJson(Collection<MinecraftObject> c) {
        return "["
                + c.stream()
                .map(MinecraftObject::asJson)
                .collect(Collectors.joining(", "))
                + "]";
    }

    @Override
    public String getArchitectInformation() {
        return "SimpleArchitect-" + instructionlevel;
    }

    public String generateResponse(Set<MinecraftObject> world,
                                   MinecraftObject target,
                                   Set<MinecraftObject> it,
                                   Orientation lastOrientation) {
        return realizer.generateInstruction(world, target, it, lastOrientation);
    }
}
