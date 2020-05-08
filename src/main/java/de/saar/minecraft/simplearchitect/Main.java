package de.saar.minecraft.simplearchitect;


import de.saar.minecraft.architect.ArchitectFactory;
import de.saar.minecraft.architect.ArchitectServer;

import java.io.IOException;

public class Main {
    /**
     * Starts an architect server.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
	int port = 10000;
	if (args.length == 1) {
	    try{
		port = Integer.parseInt(args[0]);
	    } catch (Exception ignored) {
		System.err.println("Usage: one argument only, defining the port on which the ArchitectServer listens.");
		System.exit(1);
		    
	    }
	}
        ArchitectFactory factory = () -> new SimpleArchitect();
        ArchitectServer server = new ArchitectServer(port, factory);
        server.start();
        server.blockUntilShutdown();
    }
  }
