package Agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ComputerAgent extends Agent {
    private String computerName;
    private int capacity;
    private double totalTime = 0;
    private boolean isBusy = false;
    private Map<AID, String> computers = new HashMap<>();
    private Map<AID, Boolean> tasks = new HashMap<>();
    private List<Map.Entry<AID, Integer>> myTasks = new ArrayList<>();
    private Queue<ACLMessage> CFPs = new LinkedList<>();

    protected void setup() {
        computerName = getAID().getName();
        Object[] args = getArguments();
        if (args != null && args.length > 0)
            capacity = Integer.parseInt((String) args[0]);
        else capacity = 1000;

        // Регистрация в DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("task-executing");
        sd.setName("JADE-Computer");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Добавление поведений
        addBehaviour(new RequestReceiveBehaviour());
        addBehaviour(new ProposeSendBehaviour());
        addBehaviour(new AcceptBehaviour());
        addBehaviour(new RejectBehaviour());
        addBehaviour(new TaskSearchBehaviour(this, 1000));
        addBehaviour(new SortBehaviour());
        addBehaviour(new AnswerGetBehaviour());
        //addBehaviour(new SystemShutdownBehaviour(this));

        System.out.println("ComputerAgent " + computerName + " is ready.");
    }

    protected void takeDown() {
        // Deregister in yellow pages
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Write info to file
        ObjectMapper mapper = new ObjectMapper();
        File resultsFile = new File("src/Files/results.json");
        ObjectNode root;

        try {
            if (resultsFile.exists()) root = (ObjectNode) mapper.readTree(resultsFile);
            else root = mapper.createObjectNode();

            ObjectNode computerNode = mapper.createObjectNode();
            computerNode.put("capacity", capacity); // значение по умолчанию
            computerNode.putArray("tasks");
            computerNode.put("total_time", totalTime);

            ArrayNode tasksArray = (ArrayNode) computerNode.get("tasks");
            for (Map.Entry<AID, Integer> entry : myTasks) {
                ObjectNode taskNode = mapper.createObjectNode();
                AID task = entry.getKey();
                int complexity = entry.getValue();
                taskNode.put("name", task.getName());
                taskNode.put("complexity", complexity);
                tasksArray.add(taskNode);
            }

            root.set(computerName, computerNode);
            mapper.writerWithDefaultPrettyPrinter().writeValue(resultsFile, root);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("ComputerAgent " + computerName + " is terminated.");
        printTerminationInfo();
    }

    private void printTerminationInfo() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    ComputerAGENT завершен                      ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Имя компьютера (агента):  " + computerName);
        System.out.println("Производительность:       " + capacity + " оп/с");
        System.out.println("Суммарное время:          " + String.format("%.2f", totalTime) + " с.");
        System.out.println("Назначенные задачи:       " + myTasks.size());
        System.out.println();

        if (!myTasks.isEmpty()) {
            System.out.println("Распределение задач:");
            System.out.println("─".repeat(64));
            System.out.printf("%-20s%-15s%-15s%n", "Задача", "Сложность", "% соотношение");
            System.out.println("─".repeat(64));

            int totalComplexity = myTasks.stream()
                    .mapToInt(Map.Entry::getValue)
                    .sum();

            for (Map.Entry<AID, Integer> entry : myTasks) {
                String taskName = entry.getKey().getName();
                int complexity = entry.getValue();
                double percentage = (totalComplexity > 0) ? (complexity * 100.0 / totalComplexity) : 0;
                System.out.printf("%-20s%-15d%-15.1f%%%n", taskName, complexity, percentage);
            }
            System.out.println("─".repeat(64));
            System.out.printf("%-20s%-15d%-15s%n", "Суммарно", totalComplexity, "100.0%");
            System.out.println();
        }
    }

    private class RequestReceiveBehaviour extends CyclicBehaviour {
        public void action() {
            // Receiving requests
            ACLMessage cfp = receive(MessageTemplate.MatchPerformative(ACLMessage.CFP));
            if (cfp != null) {
                CFPs.add(cfp);
                tasks.put(cfp.getSender(), false);
            } else block();
        }
    }

    private class ProposeSendBehaviour extends CyclicBehaviour {
        public void action() {
            // Sending information to complete tasks
            if (!isBusy && !CFPs.isEmpty()) {
                ACLMessage cfp = CFPs.poll();
                ACLMessage propose = cfp.createReply();
                propose.setPerformative(ACLMessage.PROPOSE);
                propose.setContent(String.valueOf((totalTime + (Double.parseDouble(cfp.getContent()) / capacity))));
                send(propose);
                isBusy = true;
            }
        }
    }

    private class AcceptBehaviour extends CyclicBehaviour {
        public void action() {
            // Receiving accept tasks
            ACLMessage accept = receive(MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));
            if (accept != null) {
                AID taskAID = accept.getSender();
                myTasks.add(Map.entry(taskAID, Integer.parseInt(accept.getContent())));
                totalTime += Double.parseDouble(accept.getContent()) / capacity;
                tasks.put(taskAID, true);
                System.out.println("ComputerAgent " + computerName + " took the " + taskAID.getName() + ".");
                isBusy = false;
            } else block();
        }
    }

    private class RejectBehaviour extends CyclicBehaviour {
        public void action() {
            // Receiving reject tasks
            ACLMessage reject = receive(MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
            if (reject != null) {
                tasks.put(reject.getSender(), true);
                isBusy = false;
            } else block();
        }
    }

    private class TaskSearchBehaviour extends TickerBehaviour {
        public TaskSearchBehaviour(Agent a, long period) {
            super(a, period);
        }

        public void onTick() {
            // Search tasks in yellow pages
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("tasks");
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                for (DFAgentDescription res : result)
                    if (!tasks.containsKey(res.getName())) tasks.put(res.getName(), false);

                // Ask question from tasks
                ACLMessage question = new ACLMessage(ACLMessage.INFORM);
                question.setOntology("Question");
                for (AID task : tasks.keySet()) question.addReceiver(task);
                send(question);

                if (!tasks.containsValue(false) && !tasks.isEmpty()) addBehaviour(new ComputerSearchBehaviour());
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    private class AnswerGetBehaviour extends CyclicBehaviour {
        public void action() {
            // Get answer to question
            ACLMessage answer = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Answer"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            if (answer != null) {
                tasks.put(answer.getSender(), Boolean.valueOf(answer.getContent()));
            } else block();
        }
    }

    private class ComputerSearchBehaviour extends OneShotBehaviour {
        public void action() {
            // Search computers in yellow pages
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("task-executing");
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                for (DFAgentDescription res : result)
                    if (!computers.containsKey(res.getName())) computers.put(res.getName(), null);

                // Send info to computers
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.setOntology("Computer-info");
                msg.setContent(String.valueOf(totalTime) + " " + String.valueOf(capacity));
                for (AID computer : computers.keySet())
                    if (!Objects.equals(computer.getName(), computerName)) msg.addReceiver(computer);
                send(msg);
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    private class SortBehaviour extends CyclicBehaviour {
        public void action() {
            // Receive info from computers
            ACLMessage msg = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Computer-info"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            if (msg != null) {
                computers.put(msg.getSender(), msg.getContent());
                computers.put(getAID(), String.valueOf(totalTime) + " " + String.valueOf(capacity));
                if (!computers.containsValue(null)) {
                    // Determining the easiest task
                    Map.Entry<AID, Integer> easyTask = null;
                    for (Map.Entry<AID, Integer> task : myTasks) {
                        if (easyTask == null) easyTask = task;
                        if (easyTask.getValue() > task.getValue()) easyTask = task;
                    }
                    if (easyTask != null) {
                        // Determining the busiest and freest computer
                        Map.Entry<AID, String> maxLoaded = null;
                        Map.Entry<AID, String> minLoaded = null;
                        for (Map.Entry<AID, String> entry : computers.entrySet()) {
                            String[] infoEntry = entry.getValue().split(" ");
                            if (maxLoaded == null || minLoaded == null) {
                                maxLoaded = entry;
                                minLoaded = entry;
                            }
                            String[] infoMax = maxLoaded.getValue().split(" ");
                            String[] infoMin = minLoaded.getValue().split(" ");
                            if (Double.parseDouble(infoMax[0]) < Double.parseDouble(infoEntry[0]))
                                maxLoaded = entry;
                            if ((Double.parseDouble(infoMin[0]) + (double) easyTask.getValue() / Integer.parseInt(infoMin[1])) >
                                    (Double.parseDouble(infoEntry[0]) + (double) easyTask.getValue() / Integer.parseInt(infoEntry[1])))
                                minLoaded = entry;
                        }
                        if (maxLoaded != null) {
                            String[] infoMax = maxLoaded.getValue().split(" ");
                            String[] infoMin = minLoaded.getValue().split(" ");
                            // That computer the busiest computer
                            if (Double.parseDouble(infoMax[0]) == totalTime) {
                                if (totalTime > (Double.parseDouble(infoMin[0]) + ((double) easyTask.getValue() / Integer.parseInt(infoMin[1])))) {
                                    // Remove task from list
                                    myTasks.remove(easyTask);
                                    totalTime -= (double) easyTask.getValue() / capacity;
                                    tasks.put(easyTask.getKey(), false);
                                    System.out.println("ComputerAgent " + computerName + " remove " + easyTask.getKey().getName() + ".");
                                    ACLMessage info = new ACLMessage(ACLMessage.INFORM);
                                    info.setOntology("Remove");
                                    info.addReceiver(easyTask.getKey());
                                    send(info);
                                }
                            }
                        }
                    }
                }
            } else block();
        }
    }

    private class SystemShutdownBehaviour extends TickerBehaviour {
        private int noChangeCount = 0;
        private String lastState = "";

        public SystemShutdownBehaviour(Agent a) {
            super(a, 5000); // Проверка каждые 5 секунд
        }

        public void onTick() {
            // Генерируем "отпечаток" текущего состояния
            String currentState = myTasks.size() + ":" + totalTime;

            // Если состояние не изменилось несколько раз подряд
            if (currentState.equals(lastState)) {
                noChangeCount++;
            } else {
                noChangeCount = 0;
            }
            lastState = currentState;

            // После 10 проверок без изменений (50 секунд) завершаемся
            if (noChangeCount >= 10) {
                System.out.println("ComputerAgent " + computerName + " is stable, shutting down.");
                doDelete(); // Вызывает takeDown() автоматически
            }
        }
    }
}
