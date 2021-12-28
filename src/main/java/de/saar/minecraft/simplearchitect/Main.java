package de.saar.minecraft.simplearchitect;


import de.saar.minecraft.architect.ArchitectFactory;
import de.saar.minecraft.architect.ArchitectServer;
import org.tinylog.Logger;
import java.io.FileReader;
import java.io.IOException;

public class Main {
    /**
     * Starts an architect server.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
		SimpleArchitectConfiguration config = new SimpleArchitectConfiguration();
		String configFile = "architect-config.yaml";
		if (args.length >= 1) {
			configFile = args[0];
		}

		FileReader fr = null;
		try {
			fr = new FileReader(configFile);
		} catch (Exception e) {
			Logger.warn("could not find architect-config.yaml, using default configuration.");
		}
		try {
			if (fr != null) {
				config = SimpleArchitectConfiguration.loadYaml(fr);
			}
		} catch (Exception e) {
			Logger.error("could not read configuration!");
			Logger.error(e);
			System.exit(1);
		}

		Logger.info("Using config: {}", config.toString());
		// make compiler happy because config is not considered final
		SimpleArchitectConfiguration myconfig = config;
        ArchitectFactory factory = () -> new SimpleArchitect(myconfig);
        int port = config.getPort();
		if (args.length >= 2) {
			port = Integer.parseInt(args[1]);
		}
        ArchitectServer server = new ArchitectServer(port, factory);
        server.start();
        server.blockUntilShutdown();
    }
  }
