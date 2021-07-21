package de.saar.minecraft.simplearchitect;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.Reader;

public class SimpleArchitectConfiguration {

    private static final String NOTSET = "__NOTSET__";
    
    private String name = "";
    private int port = 10000;
    private String secretWord = "secretWord";
    private int timeoutMinBlocks = 5;
    private int timeoutMinutes = 10;
    private boolean showSecret = true;
    private String weightSource = "default";
    private String weightFile ="";
    private String weightTrainingDatabase = "jdbc:mariadb://localhost:3306/MINECRAFT";
    private String weightTrainingDBUser = "minecraft";
    private String weightTrainingDBPassword = "";
    private String weightTrainingArchitectName = NOTSET;
    /** The percentage of games played with optimal weights in epsilongreedy setting*/
    private double epsilonGreedyPercentage = 0.5;
    private int trainingSamplingLowerPercentile = 25;
    private int trainingSamplingUpperPercentile = 75;
    private int trainingNumBootstrapRuns = 1000;
    private String instructionlevel = "BLOCK";
    private boolean addSeedGames = false;
    /** If set, overrides the plan created by the planner.*/
    private String planFile = "";

    public boolean getDeletionsAsCosts() {
        return deletionsAsCosts;
    }

    public void setDeletionsAsCosts(boolean deletionsAsCosts) {
        this.deletionsAsCosts = deletionsAsCosts;
    }

    private boolean deletionsAsCosts = false;

   public static SimpleArchitectConfiguration loadYaml(Reader reader) {
        // prepare the YAML reader to read a list of strings
        Constructor constructor = new Constructor(SimpleArchitectConfiguration.class);
        Yaml yaml = new Yaml(constructor);
        return yaml.loadAs(reader, SimpleArchitectConfiguration.class);
    }

    public String getSecretWord() {
        return secretWord;
    }

    public void setSecretWord(String secretWord) {
        this.secretWord = secretWord;
    }

    public int getTimeoutMinBlocks() {
        return timeoutMinBlocks;
    }

    public void setTimeoutMinBlocks(int timeoutMinBlocks) {
        this.timeoutMinBlocks = timeoutMinBlocks;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public boolean getShowSecret() {
        return showSecret;
    }

    public void setShowSecret(boolean showSecret) {
        this.showSecret = showSecret;
    }

    public String getWeightTrainingDatabase() {
        return weightTrainingDatabase;
    }

    public void setWeightTrainingDatabase(String weightTrainingDatabase) {
        this.weightTrainingDatabase = weightTrainingDatabase;
    }

    public int getTrainingSamplingLowerPercentile() {
        return trainingSamplingLowerPercentile;
    }

    public void setTrainingSamplingLowerPercentile(int trainingSamplingLowerPercentile) {
        this.trainingSamplingLowerPercentile = trainingSamplingLowerPercentile;
    }

    public int getTrainingSamplingUpperPercentile() {
        return trainingSamplingUpperPercentile;
    }

    public void setTrainingSamplingUpperPercentile(int trainingSamplingUpperPercentile) {
        this.trainingSamplingUpperPercentile = trainingSamplingUpperPercentile;
    }

    public int getTrainingNumBootstrapRuns() {
        return trainingNumBootstrapRuns;
    }

    public void setTrainingNumBootstrapRuns(int trainingNumBootstrapRuns) {
        this.trainingNumBootstrapRuns = trainingNumBootstrapRuns;
    }

    public String getWeightSource() {
        return weightSource;
    }

    public void setWeightSource(String weightSource) {
        this.weightSource = weightSource;
    }

    public String getWeightFile() {
        return weightFile;
    }

    public void setWeightFile(String weightFile) {
        this.weightFile = weightFile;
    }

    public String getName() {
        if (name.equals("")) {
            return "SimpleArchitect-" + instructionlevel;
        }
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean getAddSeedGames() {
        return addSeedGames;
    }

    public void setAddSeedGames(boolean addSeedGames) {
        this.addSeedGames = addSeedGames;
    }


    public double getEpsilonGreedyPercentage() {
        return epsilonGreedyPercentage;
    }

    public void setEpsilonGreedyPercentage(double epsilonGreedyPercentage) {
        this.epsilonGreedyPercentage = epsilonGreedyPercentage;
    }

    public String toYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
       var yaml = new Yaml(options);
       return yaml.dump(this);
    }

    @Override
    public String toString() {
        return toYaml();
    }

    public String getInstructionlevel() {
        return instructionlevel;
    }

    public void setInstructionlevel(String instructionlevel) {
        this.instructionlevel = instructionlevel;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getWeightTrainingDBUser() {
        return weightTrainingDBUser;
    }

    public void setWeightTrainingDBUser(String weightTrainingDBUser) {
        this.weightTrainingDBUser = weightTrainingDBUser;
    }

    public String getWeightTrainingDBPassword() {
        return weightTrainingDBPassword;
    }

    public void setWeightTrainingDBPassword(String weightTrainingDBPassword) {
        this.weightTrainingDBPassword = weightTrainingDBPassword;
    }

    public String getWeightTrainingArchitectName() {
        if (weightTrainingArchitectName.equals(NOTSET)) {
            return name;
        }
        return weightTrainingArchitectName;
    }

    public void setWeightTrainingArchitectName(String weightTrainingArchitectName) {
        this.weightTrainingArchitectName = weightTrainingArchitectName;
    }

    public String getPlanFile() {
        return planFile;
    }

    public void setPlanFile(String planFile) {
        this.planFile = planFile;
    }

}
