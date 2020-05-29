package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.minecraft.shared.WorldSelectMessage;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SimpleArchitectTest {
    @Test
    public void testOneInstruction() {
        var architect = new SimpleArchitect(new SimpleArchitectConfiguration());
        architect.initialize(WorldSelectMessage.newBuilder().setGameId(1).setName("house").build());
        var plan = architect.computePlan("house");
        assertTrue(plan.size() > 10);
        MinecraftObject objective = plan.get(0);
        while (objective instanceof IntroductionMessage) {
            IntroductionMessage obj = (IntroductionMessage) objective;
            //String message = generateResponse(world, obj.object, it, lastOrientation);
            if (obj.starting) {
                System.out.println("Now I will teach you how to build a " + obj.name);
            } else {
                System.out.println("Great! You finished building a " + obj.name);
            }
            plan.remove(0);
            objective = plan.get(0);
        }
        //System.out.println(objective.toString());
        System.out.println(architect.generateResponse(architect.world,
                objective, architect.it, Relation.Orientation.ZPLUS));
    }
}
