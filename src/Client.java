import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.ControllerException;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class Client {
    public static void main(String[] args) throws InterruptedException {
        Runtime rt = Runtime.instance();

        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "192.168.1.211");
        p.setParameter(Profile.MAIN_PORT, "1099");
        AgentContainer agentContainer = rt.createAgentContainer(p);

        // Thread.sleep(5000);
        try {
            String containerName = agentContainer.getContainerName();
            String prefix = generatePrefix(containerName);

            JsonNode tasks = new ObjectMapper().readTree(new File("src/Files/tasks.json"));
            Iterator<Map.Entry<String, JsonNode>> t = tasks.fields();

            while (t.hasNext()) {
                Map.Entry<String, JsonNode> task = t.next();
                String taskName = prefix + task.getKey();
                AgentController agent = agentContainer.createNewAgent(
                        taskName,
                        "Agents.TaskAgent",
                        new Object[]{task.getValue().path("complexity").asText()}
                );
                agent.start();
            }
        } catch (IOException | ControllerException e) {
            throw new RuntimeException(e);
        }
    }

    private static String generatePrefix(String containerName) {
        if (containerName == null || containerName.isEmpty()) {
            return "C0_";
        }
        
        String[] parts = containerName.split("[^a-zA-Z0-9]+");
        char firstLetter = 'C';
        String number = "0";
        
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (Character.isLetter(part.charAt(0))) {
                    firstLetter = Character.toUpperCase(part.charAt(0));
                }
                String digits = part.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    number = digits;
                    break;
                }
            }
        }
        
        return firstLetter + number + "_";
    }
}
