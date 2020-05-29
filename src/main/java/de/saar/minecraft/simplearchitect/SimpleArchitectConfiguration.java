package de.saar.minecraft.simplearchitect;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.Reader;

public class SimpleArchitectConfiguration {

    private String secretWord = "secretWord";
    private int timeoutMinBlocks = 5;
    private int timeoutMinutes = 10;
    private boolean showSecret = true;

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

    @Override
    public String toString() {
        return "SimpleArchitectConfiguration{" +
                "secretWord='" + secretWord + '\'' +
                ", timoutMinBlocks=" + timeoutMinBlocks +
                ", timeoutMinutes=" + timeoutMinutes +
                ", showSecret=" + showSecret +
                '}';
    }
}
