package de.saar.minecraft.simplearchitect;

import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import org.eclipse.collections.api.set.MutableSet;

import java.util.Set;

public class IntroductionMessage extends MinecraftObject {

    public final MinecraftObject object;
    public final boolean starting;
    public final String name;

    public IntroductionMessage(MinecraftObject object, boolean starting, String name){
        this.object = object;
        this.starting = starting;
        this.name = name;
    }

    @Override
    public Set<Block> getBlocks() {
        return object.getBlocks();
    }

    @Override
    public boolean sameShapeAs(MinecraftObject other) {
        return object.sameShapeAs(other);
    }

    @Override
    public MutableSet<Relation> generateRelationsTo(MinecraftObject other, Relation.Orientation orientation) {
        return object.generateRelationsTo(other, orientation);
    }

    @Override
    public String toString() {
        return "Introduction Object for: " + object.toString();
    }
}
