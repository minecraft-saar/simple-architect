package de.saar.minecraft.simplearchitect;

import org.junit.Test;

public class SimpleArchitectTest {
    @Test
    public void testOneInstruction() {
        var architect = new SimpleArchitect();
        System.out.println(architect.generateResponse());
    }
}
