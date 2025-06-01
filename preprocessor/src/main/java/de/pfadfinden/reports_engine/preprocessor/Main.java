package de.pfadfinden.reports_engine.preprocessor;

import java.util.ServiceLoader;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {

        CommandLine commandLine = new CommandLine(new DefaultCommand());

        // dynamic registration of subcommands, could also load from other jars added to
        // the classpath at runtime (Plugins)
        ServiceLoader<FollowUpTask> subcommandsLoader = ServiceLoader.load(FollowUpTask.class);
        subcommandsLoader.forEach(sub -> commandLine.addSubcommand(sub));

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

}