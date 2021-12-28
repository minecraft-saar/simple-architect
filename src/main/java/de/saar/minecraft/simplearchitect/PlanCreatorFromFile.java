package de.saar.minecraft.simplearchitect;

import umd.cs.shop.JSJshop;
import umd.cs.shop.costs.CostFunction;
import org.tinylog.Logger;

import java.io.*;
import java.nio.file.Files;

public class PlanCreatorFromFile extends PlanCreator {
    protected String planFileName;

    public PlanCreatorFromFile(String scenario, String planFileName) {
        super();
        this.planFileName = planFileName;
        this.instructionLevel = CostFunction.InstructionLevel.HIGHLEVEL;
        this.plan = computePlan(scenario);
    }

    protected String computeJShopPlan(JSJshop planner, String scenario, CostFunction.InstructionLevel instructionLevel) {
        Logger.debug("creating plan for {}", scenario);

        var initialworld = getResourceStream("/de/saar/minecraft/worlds/" + scenario + ".csv");
        var domain = getResourceStream("/de/saar/minecraft/domains/" + scenario + ".lisp");
        String problem = getResourceAsString("/de/saar/minecraft/domains/" + scenario + ".init").strip();
        planner.transformWorldForArchitect(initialworld, problem, domain);
        try {
            return Files.readString(new File(planFileName).toPath());
        } catch (IOException e) {
            Logger.error("Plan file not found: {}", planFileName);
            throw new RuntimeException("Plan file not found");
        }
    }
}
