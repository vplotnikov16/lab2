import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class Client {
    public static void main(String[] args) throws InterruptedException {
        Runtime rt = Runtime.instance();

        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "localhost");
        p.setParameter(Profile.MAIN_PORT, "1099");
        AgentContainer agentContainer = rt.createAgentContainer(p);

        // Thread.sleep(5000);
        try {
            JsonNode tasks = new ObjectMapper().readTree(new File("src/Files/tasks.json"));
            Iterator<Map.Entry<String, JsonNode>> t = tasks.fields();

            while (t.hasNext()) {
                Map.Entry<String, JsonNode> task = t.next();
                AgentController agent = agentContainer.createNewAgent(
                        task.getKey(),
                        "Agents.TaskAgent",
                        new Object[]{task.getValue().path("complexity").asText()}
                );
                agent.start();
            }
        } catch (StaleProxyException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
