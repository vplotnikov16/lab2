import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;

public class Server {
    public static void main(String[] args) throws UnknownHostException {
        Runtime rt = Runtime.instance();

        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "1099");
        p.setParameter(Profile.GUI, "true"); // Включаем GUI
        AgentContainer mainContainer = rt.createMainContainer(p);

        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode emptyRoot = mapper.createObjectNode();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File("src/Files/results.json"), emptyRoot);

            JsonNode computers = new ObjectMapper().readTree(new File("src/Files/computers.json"));
            Iterator<Map.Entry<String, JsonNode>> c = computers.fields();

            while (c.hasNext()) {
                Map.Entry<String, JsonNode> computer = c.next();
                AgentController agent = mainContainer.createNewAgent(
                        computer.getKey(),
                        "Agents.ComputerAgent",
                        new Object[]{computer.getValue().path("capacity").asText()}
                );
                agent.start();
            }

        } catch (StaleProxyException | IOException /*| InterruptedException*/ e) {
            throw new RuntimeException(e);
        }
    }
}
