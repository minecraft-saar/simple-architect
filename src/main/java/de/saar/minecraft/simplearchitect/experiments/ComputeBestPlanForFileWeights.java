package de.saar.minecraft.simplearchitect.experiments;

import de.saar.minecraft.simplearchitect.SimpleArchitectConfiguration;

import java.util.List;

/**
 * This class is a simple tool to compute which of the different possible plans (lowlevel, teaching, highlevel)
 * is the best according to each of the provided weight files.
 */
class ComputeBestPlanForFileWeights {

    public static void main(String[] args) {
        for (String scenario: List.of("house", "bridge")) {
            System.out.println("\n\n\nScenario:" + scenario);
            for (var arg : args) {
                NoiseMaxExperiment.NoiseMaxArchitect architect;
                SimpleArchitectConfiguration conf;
                conf = new SimpleArchitectConfiguration();
                conf.setWeightSource("default");
                conf.setName("adaptive-optimal");
                conf.setWeightSource("file");
                conf.setWeightFile(arg);
                System.out.println("file: " + arg);
                architect = new NoiseMaxExperiment.NoiseMaxArchitect(conf);
                // architect.getRealizer().randomizeExpectedDurations(perturbSize);
                var plan = architect.getOptimalPlan(scenario);
                System.out.println("best plan: " + plan.getInstructionLevel());
            }
        }
    }
}
