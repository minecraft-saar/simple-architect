package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.minecraft.shared.WorldSelectMessage;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SimpleArchitectTest {
    @Test
    public void testOneInstruction() {
        var architect = new SimpleArchitect();
        architect.initialize(WorldSelectMessage.newBuilder().setGameId(1).setName("house").build());
        var plan = architect.computePlan("house");
        assertTrue(plan.size() > 10);
        System.out.println(architect.generateResponse(architect.world,
                plan.get(0), architect.it, Relation.Orientation.ZPLUS));
    }
}
