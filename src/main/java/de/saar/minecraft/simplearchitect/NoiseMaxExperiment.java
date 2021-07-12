package de.saar.minecraft.simplearchitect;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

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
class NoiseMaxExperiment {

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

    SimpleArchitect architect;
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
        architect = new SimpleArchitect(conf);
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

}
