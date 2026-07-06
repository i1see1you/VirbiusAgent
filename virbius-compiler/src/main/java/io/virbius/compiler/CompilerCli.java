package io.virbius.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.virbius.groovy.l3.GroovyL3ValidationException;
import io.virbius.groovy.l3.GroovyL3Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "virbius-compiler", mixinStandardHelpOptions = true, version = "0.1.0-mvp")
public class CompilerCli implements Runnable {

    @Option(names = {"-i", "--input"}, required = true, description = "rule bundle yaml/json")
    private Path input;

    @Option(names = {"-o", "--output"}, required = true, description = "output directory")
    private Path output;

    @Option(
            names = {"-t", "--target"},
            defaultValue = "all",
            description = "compile target: all | edge | gateway | cloud")
    private String target;

    @Option(
            names = {"-g", "--gateway"},
            defaultValue = "apisix",
            description = "gateway backend when --target=gateway: apisix | openresty | all")
    private String gateway;

    @Option(
            names = {"--deploy-layout"},
            defaultValue = "staged",
            description = "openresty path layout: staged | control-data (VIRBIUS_DATA_DIR/gateway)")
    private String deployLayout;

    @Option(
            names = {"--deploy-prefix"},
            defaultValue = "/etc/virbius",
            description = "deploy root for lists_file / scene_registry_file in effective JSON")
    private String deployPrefix;

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) {
        int code = new CommandLine(new CompilerCli()).execute(args);
        System.exit(code);
    }

    @Override
    public void run() {
        try {
            JsonNode root = read(input);
            Files.createDirectories(output);
            String t = target == null ? "all" : target.trim().toLowerCase(Locale.ROOT);

            int count = 0;
            if ("edge".equals(t) || "all".equals(t)) {
                count += compileEdge(root, output);
            }
            if ("cloud".equals(t) || "all".equals(t)) {
                count += compileLayerRules(root, output, "cloud");
            }
            if ("gateway".equals(t) || "all".equals(t)) {
                count += compileLayerRules(root, output, "gateway");
                Path gwDir = output.resolve("gateway");
                String gw = gateway == null ? "apisix" : gateway.trim().toLowerCase(Locale.ROOT);
                if ("apisix".equals(gw) || "all".equals(gw)) {
                    GatewayApisixEmitter.emitService(root, gwDir, json);
                    GatewayApisixEmitter.emitSceneRegistry(root, gwDir, json);
                    int routeCount = GatewayApisixEmitter.emitRoutes(root, gwDir, json);
                    System.out.println("gateway apisix routes: " + routeCount);
                }
                if ("openresty".equals(gw) || "all".equals(gw)) {
                    VirbiusConfigMerger.DeployLayout layout =
                            VirbiusConfigMerger.parseLayout(deployLayout);
                    int orCount = GatewayOpenrestyEmitter.emit(root, gwDir, json, deployPrefix, layout);
                    System.out.println("gateway openresty routes: " + orCount);
                }
            }
            if ("edge".equals(t)) {
                System.out.println("compiled edge manifest + " + count + " edge rules -> " + output);
            } else {
                System.out.println("compiled " + count + " rules -> " + output);
            }
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage(), e);
        }
    }

    private int compileEdge(JsonNode root, Path output) throws Exception {
        EdgeManifestEmitter.write(output, root, json);
        return compileLayerRules(root, output, "edge");
    }

    private int compileLayerRules(JsonNode root, Path outDir, String layer) throws Exception {
        int count = 0;
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray()) {
            return 0;
        }
        for (JsonNode rule : rules) {
            if (!layer.equalsIgnoreCase(rule.path("layer").asText("edge"))) {
                continue;
            }
            String ruleId = rule.path("rule_id").asText("unknown");
            validateGroovy(rule, ruleId);
            Path out = outDir.resolve(layer + "-" + ruleId + ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), rule);
            count++;
        }
        return count;
    }

    private void validateGroovy(JsonNode rule, String ruleId) {
        if (!"groovy".equals(rule.path("runtime").asText(""))) {
            return;
        }
        String body = "";
        if (rule.has("body")) {
            JsonNode b = rule.get("body");
            body = b.isTextual() ? b.asText() : b.toString();
        }
        try {
            GroovyL3Validator.validate(body);
        } catch (GroovyL3ValidationException e) {
            throw new CommandLine.ExecutionException(
                    new CommandLine(this), "groovy rule " + ruleId + ": " + e.getMessage());
        }
    }

    private JsonNode read(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return yaml.readTree(Files.readString(path));
        }
        return json.readTree(Files.readString(path));
    }
}
