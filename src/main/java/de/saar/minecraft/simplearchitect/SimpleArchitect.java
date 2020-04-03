package de.saar.minecraft.simplearchitect;

import com.google.common.collect.Iterables;
import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.Row;
import de.saar.coli.minecraft.relationextractor.Wall;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.coli.minecraft.relationextractor.Relation.Orientation;
import de.saar.coli.minecraft.relationextractor.UniqueBlock;
import de.saar.minecraft.architect.AbstractArchitect;
import de.saar.minecraft.shared.BlockDestroyedMessage;
import de.saar.minecraft.shared.BlockPlacedMessage;
import de.saar.minecraft.shared.NewGameState;
import de.saar.minecraft.shared.StatusMessage;
import de.saar.minecraft.shared.TextMessage;
import de.saar.minecraft.shared.WorldSelectMessage;
import de.up.ling.irtg.algebra.ParserException;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import umd.cs.shop.JSJshop;
import umd.cs.shop.JSState;
import umd.cs.shop.JSPredicateForm;
import umd.cs.shop.JSTerm;
import umd.cs.shop.JSPlan;
import umd.cs.shop.JSTaskAtom;
import umd.cs.shop.costs.CostFunction;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

public class SimpleArchitect extends AbstractArchitect {
    private static Logger logger = LogManager.getLogger(SimpleArchitect.class);
    protected Set<MinecraftObject> it = Set.of();
    private List<MinecraftObject> plan;
    private Set<Block> currentInstructionBlocksLeft = Set.of();
    protected Set<MinecraftObject> world;
    private MinecraftRealizer realizer;
    protected AtomicInteger numInstructions = new AtomicInteger(0);
    protected AtomicLong lastUpdate = new AtomicLong(0);
    protected String currentInstruction;
    protected Orientation lastOrientation = Orientation.ZPLUS;
    protected String scenario;

    protected Set<Block> alreadyPlacedBlocks = new HashSet<>();

    //enum InstructionLevel{
    //    BLOCK,
    //    MEDIUM,
    //    HIGHLEVEL
    //}

    public SimpleArchitect() {

    }

    @Override
    public void playerReady() {
        sendMessage("Welcome! I will try to instruct you to build a " + scenario);
    }

    private static InputStream getResourceStream(String resName) {
        return SimpleArchitect.class.getResourceAsStream(resName);
    }

    private static String getResourceAsString(String resName) {
        return new BufferedReader(new InputStreamReader(getResourceStream(resName)))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    private static CostFunction.InstructionLevel getInstructionLevel() {
        var instructionlevel = System.getProperty("instructionlevel", "BLOCK");
        return CostFunction.InstructionLevel.valueOf(instructionlevel);
    }

    private JSPlan createPlan(JSJshop planner, String screnario) {
        int mctsruns = 10000; //number of runs the planner tries to do
        int timeout = 10000; //time the planner runs in ms

        var initialworld = getResourceStream("/de/saar/minecraft/worlds/" + scenario + ".csv");
        var domain = getResourceStream("/de/saar/minecraft/domains/" + scenario + "-block.lisp");
        String problem = getResourceAsString("/de/saar/minecraft/domains/" + scenario + "-block.init").strip();
        CostFunction.InstructionLevel level = getInstructionLevel();
        return planner.nlgSearch(mctsruns, timeout, initialworld, problem, domain, level);
    }

    @Override
    public void initialize(WorldSelectMessage message) {
        setGameId(message.getGameId());
        scenario = message.getName();
        this.plan = computePlan(scenario);
        currentInstruction = generateResponse(world, plan.get(0), it);
        currentInstructionBlocksLeft = plan.get(0).getBlocks();
    }

    public List<MinecraftObject> computePlan(String scenario) {
        JSJshop planner = new JSJshop();
        var jshopPlan = createPlan(planner, scenario);
        this.realizer = MinecraftRealizer.createRealizer();
        world = transformState(planner.prob.state());
        return transformPlan(jshopPlan);
    }

    @Override
    public void setMessageChannel(StreamObserver<TextMessage> messageChannel) {
        super.setMessageChannel(messageChannel);
    }

    public List<MinecraftObject> transformPlan(JSPlan jshopPlan) {
        var result = new ArrayList<MinecraftObject>();
        JSTaskAtom t;
        for (short i = 0; i < jshopPlan.size(); i++) {
            t = (JSTaskAtom) jshopPlan.elementAt(i);
            String task = t.toStr().toString();
            String[] taskArray = task.split(" ");
            int x1, y1, z1, x2, y2, z2, length, height, dir;
            switch (taskArray[0]) {
                case "(!place-block":
                    x1 = (int) Double.parseDouble(taskArray[2]);
                    y1 = (int) Double.parseDouble(taskArray[3]);
                    z1 = (int) Double.parseDouble(taskArray[4]);
                    result.add(new Block(x1, y1, z1));
                    break;
                case "(!build-row":
                    x1 = (int) Double.parseDouble(taskArray[1]);
                    y1 = (int) Double.parseDouble(taskArray[2]);
                    z1 = (int) Double.parseDouble(taskArray[3]);
                    length = (int) Double.parseDouble(taskArray[4]);
                    dir = (int) Double.parseDouble(taskArray[5]);
                    //east=1=>x+, west=2=>x-, north=3=>y-, south=4=>y+
                    if (dir == 1) {
                        x2 = x1 + length - 1;
                        z2 = z1;
                    } else if (dir == 2) {
                        x2 = x1 - length + 1;
                        z2 = z1;
                    } else if (dir == 3) {
                        x2 = x1;
                        z2 = z1 - length + 1;
                    } else if(dir == 4) {
                        x2 = x1;
                        z2 = z1 + length - 1;
                    } else {
                        System.err.println("Unkown direction in plan transformation");
                        break;
                    }
                    result.add(new Row("row", x1, z1, x2, z2, y1));
                    break;
                case "(!build-wall":
                    x1 = (int) Double.parseDouble(taskArray[1]);
                    y1 = (int) Double.parseDouble(taskArray[2]);
                    z1 = (int) Double.parseDouble(taskArray[3]);
                    length = (int) Double.parseDouble(taskArray[4]);
                    height = (int) Double.parseDouble(taskArray[5]);
                    dir = (int) Double.parseDouble(taskArray[6]);
                    //east=1=>x+, west=2=>x-, north=3=>y-, south=4=>y+

                    if (dir == 1) {
                        x2 = x1 + length - 1;
                        z2 = z1;
                    } else if (dir == 2) {
                        x2 = x1 - length + 1;
                        z2 = z1;
                    } else if (dir == 3) {
                        x2 = x1;
                        z2 = z1 - length + 1;
                    } else if(dir == 4) {
                        x2 = x1;
                        z2 = z1 + length - 1;
                    } else {
                        System.err.println("Unkown direction in plan transformation");
                        break;
                    }
                    y2 = y1 + height -1;
                    result.add(new Wall("row", x1, y1, z1, x2, y2, z2));
                    break;
                default:
                    System.out.println("New Action "+ task);
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
            JSTerm tmp = (JSTerm) term.elementAt(2);
            int x = (int) Double.parseDouble(tmp.toStr().toString());
            tmp = (JSTerm) term.elementAt(3);
            int y = (int) Double.parseDouble(tmp.toStr().toString());
            ;
            tmp = (JSTerm) term.elementAt(4);
            int z = (int) Double.parseDouble(tmp.toStr().toString());
            ;
            //System.out.println("Block: " + type + " " + x + " " + y + " "+ z);
            set.add(new UniqueBlock(type, x, y, z));
        }
        return set;
    }

    @Override
    public void handleBlockPlaced(BlockPlacedMessage request) {
        int currNumInstructions = numInstructions.incrementAndGet();

        synchronized (realizer) {
            if (currNumInstructions < numInstructions.get()) {
                // other instructions were planned after this one,
                // our information is outdated, so skip this one
                return;
            }
            lastUpdate.set(java.lang.System.currentTimeMillis());
            int x = request.getX();
            int y = request.getY();
            int z = request.getZ();
            var blockPlaced = new Block(x, y, z);
            String response = "";
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
            // current object is complete                // current object is complete                // current object is complete
            if (currentInstructionBlocksLeft.size() == 1 && currentInstructionBlocksLeft.contains(blockPlaced)) {
                world.add(blockPlaced);
                it = Set.of(blockPlaced);
                plan.remove(0);
                if (plan.isEmpty()) {
                    sendMessage("Congratulations, you are done building a " + scenario,
                            NewGameState.SuccessfullyFinished);
                } else {
                    var currentObjective = plan.get(0);
                    currentInstructionBlocksLeft = currentObjective.getBlocks();
                    currentInstruction = generateResponse(world, plan.get(0), it);
                    sendMessage("Great! now " + currentInstruction);
                }
                alreadyPlacedBlocks.add(blockPlaced);
                return;
            }
            //
            if (currentInstructionBlocksLeft.contains(blockPlaced)) {
                // correct block, but objective not complete
                // Just note and do nothing for now
                currentInstructionBlocksLeft.remove(blockPlaced);
                alreadyPlacedBlocks.add(blockPlaced);
                it = Set.of(blockPlaced);
            } else {
                sendMessage("Not there! please remove that block again and " + currentInstruction);
            }
        }
    }

    @Override
    public void handleBlockDestroyed(BlockDestroyedMessage request) {
        // TODO add logic to only say this if the previous block was correct
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        var block = new Block(x, y, z);
        // If a block that should be placed is removed again, re-add it to the plan
        // and instruct the user to place this block again
        if (alreadyPlacedBlocks.contains(block)) {
            it = Set.of(); // We cannot say "previous block" when the last action was a removal
            alreadyPlacedBlocks.remove(block);
            plan.add(0, block);
            sendMessage("Please add this block again.");
            currentInstruction = generateResponse(world, plan.get(0), it);
            lastUpdate.set(java.lang.System.currentTimeMillis());
        } else {
            // Just ignore the vandalism.
        }
    }

    @Override
    public void handleStatusInformation(StatusMessage request) {
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
            currentInstruction = generateResponse(world, plan.get(0), it);
        }
        lastUpdate.set(java.lang.System.currentTimeMillis());
        sendMessage(currentInstruction);
    }


    @Override
    public String getArchitectInformation() {
        return "SimpleArchitect";
    }

    public String generateResponse(Set<MinecraftObject> world, MinecraftObject target, Set<MinecraftObject> it) {
        var response = "";
        var relations = Relation.generateAllRelationsBetweeen(
                Iterables.concat(world,
                        org.eclipse.collections.impl.factory.Iterables.iList(target)
                ),
                lastOrientation
        );
        for (var elem : it) {
            relations.add(new Relation("it", elem));
        }
        try {
            realizer.setRelations(relations);
            response = realizer.generateStatement("put", target, "type");
        } catch (ParserException e) {
            e.printStackTrace();
        }
        return response;
    }
}
