package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.relationextractor.*;
import umd.cs.shop.JSJshop;
import umd.cs.shop.JSPredicateForm;
import umd.cs.shop.JSState;
import umd.cs.shop.JSTerm;
import umd.cs.shop.costs.CostFunction;
import org.tinylog.Logger;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PlanCreator {
    protected static InputStream getResourceStream(String resName) {
        return PlanCreator.class.getResourceAsStream(resName);
    }

    protected static String getResourceAsString(String resName) {
        return new BufferedReader(new InputStreamReader(getResourceStream(resName)))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    protected Set<MinecraftObject> world;
    protected List<MinecraftObject> plan;
    protected CostFunction.InstructionLevel instructionLevel;
    
    protected PlanCreator(){}

    public PlanCreator(String scenario, CostFunction.InstructionLevel instructionLevel) {
        this.instructionLevel = instructionLevel;
        this.plan = computePlan(scenario);
    }

    /**
     * Returns a copy of the initial world (so the consumer can freely edit the set).
     */
    public Set<MinecraftObject> getInitialWorld() {
        return new HashSet<>(world);
    }

    /**
     * returns a copy of the current plan (so the consumer can freely edit the list).
     */
    public List<MinecraftObject> getPlan() {
        return new ArrayList<>(plan);
    }
    
    public String getInstructionLevel() {
        return instructionLevel.toString();
    }

    /**
     * Extracts all blocks existing in the current world -- also extracts blocks from all high-level objects.
     * @return a set of all blocks currently in the world.
     */
    public Set<Block> getBlocksCurrentWorld() {
        HashSet<Block> alreadyPlacedBlocks = new HashSet<>();
        world.forEach((x) ->
                x.getBlocks().forEach((block) ->
                        alreadyPlacedBlocks.add(new Block(block.xpos, block.ypos, block.zpos))
                )
        );
        return alreadyPlacedBlocks;
    }

    /**
     * Assembles all relevant resources and runs the {@code planner} on it.
     * Currently it instead runs the planner for one iteration an then returns a precomputed plan
     * to make the system real-time compatible.
     * @param planner a Jshop planner
     * @param scenario e.g. "house", "bridge"
     * @param instructionLevel at what abstraction level the plan should be
     * @return a plan represented as an s-expression String
     */
    protected String computeJShopPlan(JSJshop planner, String scenario, CostFunction.InstructionLevel instructionLevel) {
        Logger.debug("Creating plan for {}", scenario);
        var initialworld = getResourceStream("/de/saar/minecraft/worlds/" + scenario + ".csv");
        var domain = getResourceStream("/de/saar/minecraft/domains/" + scenario + ".lisp");
        String problem = getResourceAsString("/de/saar/minecraft/domains/" + scenario + ".init").strip();
        planner.transformWorldForArchitect(initialworld, problem, domain);
        String precomputedFileName = scenario + "-" + instructionLevel.name().toLowerCase() + ".plan";
        return getResourceAsString("/de/saar/minecraft/domains/" + precomputedFileName);
    }

    protected List<MinecraftObject> computePlan(String scenario) {
        Logger.debug("computing plan");
        JSJshop planner = new JSJshop();
        var jshopPlan = computeJShopPlan(planner, scenario, this.instructionLevel);

        world = transformState(planner.prob.state());
        // note which blocks already exist in the world.
        Logger.debug("plan computed");
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

    public MinecraftObject createStairs(String[] taskArray) {
        int x1, x2, x3, y1, y3, z1, z2, z3, length, width, height, dir;
        x1 = (int) Double.parseDouble(taskArray[1]);
        y1 = (int) Double.parseDouble(taskArray[2]);
        z1 = (int) Double.parseDouble(taskArray[3]);
        width = (int) Double.parseDouble(taskArray[4]);
        length =  (int) Double.parseDouble(taskArray[5]);
        height =  (int) Double.parseDouble(taskArray[6]);
        dir =  (int) Double.parseDouble(taskArray[7]);
        //east=1=>x+, west=2=>x-, north=3=>z-, south=4=>z+
        if (dir == 1) {
            x2 = x1 + width - 1;
            z2 = z1;
            x3 = x1;
            z3 = z1 + length - 1;
        } else if (dir == 2) {
            x2 = x1;
            x1 = x1 - width + 1;
            z2 = z1;
            x3 = x1;
            z3 = z1 - length + 1;
        } else if (dir == 3) {
            z2 = z1;
            z1 = z1 - width + 1;
            x2 = x1;
            z3 = z1;
            x3 = x1 + length -1;
        } else {
            z2 = z1 + width - 1;
            x2 = x1;
            z3 = z1;
            x3 = x1 - length + 1;
        }
        y3 = y1 + height -1;
        return new Stairs( "staircase", x1, y1, z1, x2, z2, x3, y3, z3);
    }

    public List<MinecraftObject> transformPlan(String jshopPlan) {
        var result = new ArrayList<MinecraftObject>();
        String[] tasks = jshopPlan.split("\n");
        for (String task : tasks) {
            //t = (JSTaskAtom) jshopPlan.elementAt(i);
            //String task = tasks[i];
            //log(task, "Plan");
            String[] taskArray = task.split(" ");
            int x1, y1, z1; // , x2, y2, z2, length, width, height, dir;
            // boolean inst = false;
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
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRow(taskArray), true, "row"));
                    break;
                case "(!build-row-finished":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRow(taskArray), false, "row"));
                    break;
                case "(!build-wall-starting":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createWall(taskArray), true, "wall"));
                    break;
                case "(!build-wall-finished":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createWall(taskArray), false, "wall"));
                    break;
                case "(!build-wall":
                    result.add(createWall(taskArray));
                    break;
                case "(!build-railing-starting":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRailing(taskArray), true, "railing"));
                    break;
                case "(!build-railing-finished":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createRailing(taskArray), false, "railing"));
                    break;
                case "(!build-railing":
                    result.add(createRailing(taskArray));
                    break;
                case "(!build-floor-starting":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createFloor(taskArray), true, "floor"));
                    break;
                case "(!build-floor-finished":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createFloor(taskArray), false, "floor"));
                    break;
                case "(!build-floor":
                    result.add(createFloor(taskArray));
                    break;
                case "(!build-stairs-starting":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createStairs(taskArray), true, "staircase"));
                    break;
                case "(!build-stairs-finished":
                    if (instructionLevel != CostFunction.InstructionLevel.BLOCK)
                        result.add(new IntroductionMessage(createStairs(taskArray), false, "staircase"));
                    break;
                case "(!build-stairs":
                    result.add(createStairs(taskArray));
                    break;
                case "(!place-block-hidden":
                case "(!remove-it-row":
                case "(!remove-it-railing":
                case "(!remove-it-wall":
                case "(!remove-it-stairs":
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
        HashSet<MinecraftObject> set = new HashSet<>();
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

}
