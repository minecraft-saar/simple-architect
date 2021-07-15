package de.saar.minecraft.simplearchitect;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import umd.cs.shop.JSJshop;
import umd.cs.shop.costs.CostFunction;

import java.io.*;
import java.nio.file.Files;

public class PlanCreatorFromFile extends PlanCreator {

    private static final Logger logger = LogManager.getLogger(PlanCreatorFromFile.class);
    protected String planFileName;

    public PlanCreatorFromFile(String scenario, CostFunction.InstructionLevel instructionLevel, String planFileName) {
        super();
        this.planFileName = planFileName;
        this.instructionLevel = instructionLevel;
        this.plan = computePlan(scenario);
    }

    protected String computeJShopPlan(JSJshop planner, String scenario, CostFunction.InstructionLevel instructionLevel) {
        logger.debug("creating plan for " + scenario);
        int mctsruns = 1; //number of runs the planner tries to do
        int timeout = 10000; //time the planner runs in ms

        var initialworld = getResourceStream("/de/saar/minecraft/worlds/" + scenario + ".csv");
        var domain = getResourceStream("/de/saar/minecraft/domains/" + scenario + ".lisp");
        String problem = getResourceAsString("/de/saar/minecraft/domains/" + scenario + ".init").strip();
        planner.nlgSearch(mctsruns, timeout, initialworld, problem, domain, instructionLevel);
        String precomputedFileName = scenario + "-" + instructionLevel.name().toLowerCase() + ".plan";
        try {
            return Files.readString(new File(planFileName).toPath());
        } catch (IOException e) {
            logger.error("plan file not found: " + planFileName);
            throw new RuntimeException("Plan file not found");
        }
    }
}
