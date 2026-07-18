package com.jsonweave.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonweave.Jsonweave;
import com.jsonweave.SpecException;
import com.jsonweave.Transform;
import com.jsonweave.TransformException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Command-line runner: {@code jsonweave -s spec.json input.json}, or pipe the
 * input through stdin. Exit codes: 0 success, 1 I/O or usage problem, 2 invalid
 * spec, 3 transformation failure.
 */
@Command(name = "jsonweave",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Applies a Jsonweave spec to a JSON document.")
public final class Main implements Callable<Integer> {

    @Option(names = {"-s", "--spec"}, required = true, description = "Spec file (JSON).")
    Path specFile;

    @Parameters(index = "0", arity = "0..1",
            description = "Input JSON file; reads stdin when omitted.")
    Path inputFile;

    @Option(names = {"-o", "--output"}, description = "Output file; writes stdout when omitted.")
    Path outputFile;

    @Option(names = {"-c", "--compact"}, description = "Compact output (default is pretty-printed).")
    boolean compact;

    @Option(names = {"-e", "--expressions"},
            description = "Expression catalog file (JSON) for #expr references.")
    Path expressionsFile;

    @Option(names = "--no-mvel", description = "Disable the #mvel expression engine.")
    boolean noMvel;

    @Option(names = "--no-js", description = "Disable the #js expression engine.")
    boolean noJs;

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        PrintWriter err = new PrintWriter(System.err, true);
        Transform transform;
        try {
            Jsonweave.Builder builder = Jsonweave.builder();
            if (!noMvel) {
                builder.registerExpressionEngine("mvel", new com.jsonweave.mvel.MvelEngine());
            }
            if (!noJs) {
                builder.registerExpressionEngine("js", new com.jsonweave.js.JsEngine());
            }
            if (expressionsFile != null) {
                builder.expressions(mapper.readTree(Files.readString(expressionsFile)));
            }
            transform = builder.compile(mapper.readTree(Files.readString(specFile)));
        } catch (SpecException bad) {
            err.println("invalid spec: " + bad.getMessage());
            return 2;
        } catch (IOException io) {
            err.println("cannot read spec or expressions file: " + io.getMessage());
            return 1;
        }
        JsonNode input;
        try {
            String text = inputFile != null
                    ? Files.readString(inputFile)
                    : new String(System.in.readAllBytes());
            input = mapper.readTree(text);
        } catch (IOException io) {
            err.println("cannot read input: " + io.getMessage());
            return 1;
        }
        try {
            JsonNode output = transform.apply(input);
            String text = compact
                    ? mapper.writeValueAsString(output)
                    : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            if (outputFile != null) {
                Files.writeString(outputFile, text + System.lineSeparator());
            } else {
                System.out.println(text);
            }
            return 0;
        } catch (TransformException failed) {
            err.println("transform failed: " + failed.getMessage());
            return 3;
        } catch (IOException io) {
            err.println("cannot write output: " + io.getMessage());
            return 1;
        }
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            return new String[]{"jsonweave " + (v != null ? v : "dev")};
        }
    }
}
