package de.saar.minecraft.simplearchitect;


import de.saar.minecraft.architect.ArchitectFactory;
import de.saar.minecraft.architect.ArchitectServer;

import java.io.IOException;

public class Main {
    /**
     * Starts an architect server.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        ArchitectFactory factory = () -> new SimpleArchitect();
        ArchitectServer server = new ArchitectServer(10000, factory);
        server.start();
        server.blockUntilShutdown();
    }
  }
