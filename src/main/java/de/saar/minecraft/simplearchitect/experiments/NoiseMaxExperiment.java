package de.saar.minecraft.simplearchitect.experiments;

import de.saar.coli.minecraft.relationextractor.IntroductionMessage;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.minecraft.shared.WorldSelectMessage;
import de.saar.minecraft.simplearchitect.PlanCreator;
import de.saar.minecraft.simplearchitect.PlanCreatorFromFile;
import de.saar.minecraft.simplearchitect.SimpleArchitect;
import de.saar.minecraft.simplearchitect.SimpleArchitectConfiguration;
import umd.cs.shop.costs.CostFunction;
import org.tinylog.Logger;
import java.util.*;

/**
 * This class is a one-off experiment that investigates the effect of adding noise to learned weights.
 * The background is that we wanted to do a explore/exploit tradeoff and adding noise similar to the
 * gumbel soft-max trick was one option.
 * Result: In the data we used, the teaching strategy was best, with more and more noise, the highlevel
 * (and to lesser extend lowlevel) was more and more prominent.
 *
 * I think this is because the noise dominates the learned weights and we either have a generally positive
 * weights favoring short instructions
 */
public class NoiseMaxExperiment {

    public static void main(String[] args) {
        var nme = new NoiseMaxExperiment();
        for (int perturbSize = 2000; perturbSize<2000000; perturbSize=perturbSize*10) {
            var res  = nme.runExperiment("bridge", 10, perturbSize);
            System.out.println(perturbSize);
            for (var e: res.entrySet()) {
                System.out.println(e.getKey() + ":" + e.getValue());
            }
        }
    }

    NoiseMaxArchitect architect;
    SimpleArchitectConfiguration conf;
    public NoiseMaxExperiment() {
        conf = new SimpleArchitectConfiguration();
        conf.setWeightSource("default");
        conf.setName("adaptive-optimal");
        
        conf.setWeightSource("optimal");
        conf.setWeightTrainingArchitectName("");
        conf.setWeightTrainingDatabase("jdbc:mariadb://localhost:3306/RANDOMOPTIMALEVALBRIDGE");
    }
    
    public Map<String,Integer> runExperiment(String scenario, int numRuns, int perturbSize) {
        HashMap<String, Integer> result = new HashMap<>();
        for (int i = 0; i < numRuns; i++) {
            String res = oneRun(scenario, perturbSize);
            result.putIfAbsent(res, 0);
            result.put(res, result.get(res)+1);
        }
        return result;
    }

    public String oneRun(String scenario, int perturbSize) {
        architect = new NoiseMaxArchitect(conf);
        // System.out.println(architect.weights.toJson());
        var rand = new Random();
        for (var entry: architect.weights.weights.entrySet()) {
            entry.setValue(entry.getValue() - (rand.nextDouble()-0.5) * perturbSize);
        }
        for (var entry: architect.weights.firstOccurenceWeights.entrySet()) {
            entry.setValue(entry.getValue() - (rand.nextDouble()-0.5) * perturbSize);
        }
        // architect.getRealizer().randomizeExpectedDurations(perturbSize);
	    var plan = architect.getOptimalPlan(scenario);
	    return plan.getInstructionLevel();
    }

    static class NoiseMaxArchitect extends SimpleArchitect {
        public NoiseMaxArchitect(SimpleArchitectConfiguration config) {
            super(config);
        }

        /**
         * computes the plan and initializes the world.
         */
        @Override
        public synchronized void initialize(WorldSelectMessage message) {
            Logger.debug("initializing with {}", message);
            setGameId(message.getGameId());
            scenario = message.getName();
            String instructionlevel = config.getInstructionlevel();
            if (!config.getPlanFile().isEmpty()) {
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
            Logger.debug("initialization done");
            readyCounter.countDown();
        }

        public PlanCreator getOptimalPlan(String scenario) {
            PlanCreator argmin = null;
            double min = Double.POSITIVE_INFINITY;
            for (var il : CostFunction.InstructionLevel.values()) {
                Logger.warn("trying instruction level " + il);
                var planCreator = new PlanCreator(scenario, il);
                double cost = getCostForPlanCreator(planCreator);
                Logger.warn("cost: " + cost);
                if (cost < min) {
                    argmin = planCreator;
                    min = cost;
                }
            }
            return argmin;
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
            Logger.debug("computing cost for {}", planCreator.getInstructionLevel());
            var tmpplan = planCreator.getPlan();
            var tmpworld = planCreator.getInitialWorld();
            double totalCost = 0;
            HashSet<MinecraftObject> it = new HashSet<>();
            Set<String> knownOjbectTypes = new HashSet<>();
            for (var mco : tmpplan) {
                if (mco instanceof IntroductionMessage im) {
                    if (!im.starting) {
                        tmpworld.add(im.object);
                        it.add(im.object);
                        knownOjbectTypes.add(im.object.getClass().getSimpleName().toLowerCase());
                    }
                    continue;
                }
                String currentObjectType = mco.getClass().getSimpleName().toLowerCase();
                Logger.debug("current object {}", currentObjectType);
                boolean objectFirstOccurence = !knownOjbectTypes.contains(currentObjectType);
                if (objectFirstOccurence && weights != null) {
                    // temporarily set the weight to the first occurence one
                    // ... if we have an estimate for the first occurence
                    if (weights.firstOccurenceWeights.containsKey("i" + currentObjectType)) {
                        realizer.setExpectedDurations(
                                Map.of("i" + currentObjectType, weights.firstOccurenceWeights.get("i" + currentObjectType)),
                                false);
                    }
                }
                var tree = realizer.generateReferringExpressionTree(tmpworld, mco, it, Relation.Orientation.XMINUS);
                if (printInstructions) {
                    System.out.println(tree);
                    System.out.println(realizer.treeToReferringExpression(tree) + " (" + -realizer.getWeightForTree(tree) + ")");
                }
                if (tree == null) {
                    Logger.warn("tree is null in the following context: ");
                    Logger.warn("current target: {}", mco);
                    Logger.warn("current world: {}", toJson(tmpworld));
                    Logger.warn("it: {}", toJson(it));
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
    }
}
