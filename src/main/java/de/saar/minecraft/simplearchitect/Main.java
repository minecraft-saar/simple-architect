package de.saar.minecraft.simplearchitect;


import de.saar.minecraft.architect.ArchitectFactory;
import de.saar.minecraft.architect.ArchitectServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.io.IOException;

public class Main {
    /**
     * Starts an architect server.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
    	var logger = LogManager.getLogger(Main.class);
		int port = 10000;
		if (args.length == 1) {
			try{
				port = Integer.parseInt(args[0]);
			} catch (Exception ignored) {
				System.err.println("Usage: one argument only, defining the port on which the ArchitectServer listens.");
				System.exit(1);
			}
		}
		SimpleArchitectConfiguration config = new SimpleArchitectConfiguration();;
		FileReader fr = null;
		try {
			fr = new FileReader("architect-config.yaml");
		} catch (Exception e) {
			logger.warn("could not find architect-config.yaml, using default configuration.");
		}
		try {
			if (fr != null) {
				config = SimpleArchitectConfiguration.loadYaml(fr);
			}
		} catch (Exception e) {
			logger.error("could not read configuration!");
			logger.error(e);
			System.exit(1);
		}
		logger.info("Using config: " + config.toString());
		// make compiler happy because config is not considered final
		SimpleArchitectConfiguration myconfig = config;
        ArchitectFactory factory = () -> new SimpleArchitect(myconfig);
        ArchitectServer server = new ArchitectServer(port, factory);
        server.start();
        server.blockUntilShutdown();
    }
  }
