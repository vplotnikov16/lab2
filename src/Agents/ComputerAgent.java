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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ComputerAgent extends Agent {
    private String computerName;
    private int capacity;
    private double totalTime = 0;
    private double delta = 2.0;
    private Map<AID, String> computers = new HashMap<>();
    private Map<AID, Boolean> tasks = new HashMap<>();
    private List<Map.Entry<AID, Integer>> myTasks = new ArrayList<>();
    private Queue<ACLMessage> CFPs = new LinkedList<>();
    
    // Парная балансировка
    private AID currentPartner = null;
    private boolean isBalancing = false;
    private int unchangedRounds = 0;
    private double lastTotalTime = 0;
    private int lastTaskCount = 0;
    private int totalTasksInSystem = 0;
    private boolean balancingInitiated = false;

    protected void setup() {
        computerName = getAID().getName();
        Object[] args = getArguments();
        if (args != null && args.length > 0)
            capacity = Integer.parseInt((String) args[0]);
        else capacity = 1000;

        loadDelta();

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

        addBehaviour(new RequestReceiveBehaviour());
        addBehaviour(new ProposeSendBehaviour());
        addBehaviour(new AcceptBehaviour());
        addBehaviour(new RejectBehaviour());
        addBehaviour(new TaskSearchBehaviour(this, 1000));
        addBehaviour(new AnswerGetBehaviour());
        addBehaviour(new PairBalancingBehaviour());

        System.out.println("ComputerAgent " + computerName + " is ready. Delta=" + delta);
    }

    private void loadDelta() {
        try {
            String content = new String(Files.readAllBytes(Paths.get("src/Files/delta.txt"))).trim();
            delta = Double.parseDouble(content);
        } catch (IOException | NumberFormatException e) {
            System.err.println("Warning: Could not read delta.txt, using default value 2.0");
            delta = 2.0;
        }
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        ObjectMapper mapper = new ObjectMapper();
        File resultsFile = new File("src/Files/results.json");
        ObjectNode root;

        try {
            if (resultsFile.exists()) root = (ObjectNode) mapper.readTree(resultsFile);
            else root = mapper.createObjectNode();

            ObjectNode computerNode = mapper.createObjectNode();
            computerNode.put("capacity", capacity);
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
            ACLMessage cfp = receive(MessageTemplate.MatchPerformative(ACLMessage.CFP));
            if (cfp != null) {
                CFPs.add(cfp);
                tasks.put(cfp.getSender(), false);
            } else block();
        }
    }

    private class ProposeSendBehaviour extends CyclicBehaviour {
        public void action() {
            if (!CFPs.isEmpty()) {
                ACLMessage cfp = CFPs.poll();
                ACLMessage propose = cfp.createReply();
                propose.setPerformative(ACLMessage.PROPOSE);
                propose.setContent(String.valueOf((totalTime + (Double.parseDouble(cfp.getContent()) / capacity))));
                send(propose);
            } else block();
        }
    }

    private class AcceptBehaviour extends CyclicBehaviour {
        public void action() {
            ACLMessage accept = receive(MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));
            if (accept != null) {
                AID taskAID = accept.getSender();
                myTasks.add(Map.entry(taskAID, Integer.parseInt(accept.getContent())));
                totalTime += Double.parseDouble(accept.getContent()) / capacity;
                tasks.put(taskAID, true);
                System.out.println("ComputerAgent " + computerName + " took the " + taskAID.getName() + ".");
            } else block();
        }
    }

    private class RejectBehaviour extends CyclicBehaviour {
        public void action() {
            ACLMessage reject = receive(MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
            if (reject != null) {
                tasks.put(reject.getSender(), true);
            } else block();
        }
    }

    private class TaskSearchBehaviour extends TickerBehaviour {
        public TaskSearchBehaviour(Agent a, long period) {
            super(a, period);
        }

        public void onTick() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("tasks");
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                
                // Проверка на добавление новых задач
                int currentTaskCount = result.length;
                if (currentTaskCount > totalTasksInSystem) {
                    System.out.println("[" + computerName + "] Detected new tasks! Resetting balancing.");
                    unchangedRounds = 0;
                    balancingInitiated = false;
                    totalTasksInSystem = currentTaskCount;
                }
                
                for (DFAgentDescription res : result)
                    if (!tasks.containsKey(res.getName())) tasks.put(res.getName(), false);

                ACLMessage question = new ACLMessage(ACLMessage.INFORM);
                question.setOntology("Question");
                for (AID task : tasks.keySet()) question.addReceiver(task);
                send(question);

                // Начинаем балансировку, если все задачи распределены
                if (!tasks.containsValue(false) && !tasks.isEmpty() && !balancingInitiated) {
                    balancingInitiated = true;
                    addBehaviour(new InitiateBalancingBehaviour());
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }
    }

    private class AnswerGetBehaviour extends CyclicBehaviour {
        public void action() {
            ACLMessage answer = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Answer"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            if (answer != null) {
                tasks.put(answer.getSender(), Boolean.valueOf(answer.getContent()));
            } else block();
        }
    }

    private class InitiateBalancingBehaviour extends OneShotBehaviour {
        public void action() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("task-executing");
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                
                // Очистка только нульевых значений
                for (DFAgentDescription res : result)
                    if (!computers.containsKey(res.getName())) computers.put(res.getName(), null);

                // Отправляем свои данные
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

    private class PairBalancingBehaviour extends CyclicBehaviour {
        private boolean readyToPair = false;
        
        public void action() {
            // Прием данных от других компьютеров
            ACLMessage msg = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Computer-info"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            
            if (msg != null) {
                computers.put(msg.getSender(), msg.getContent());
                computers.put(getAID(), String.valueOf(totalTime) + " " + String.valueOf(capacity));
                
                if (!computers.containsValue(null) && !isBalancing) {
                    // Все компьютеры ответили
                    startPairBalancing();
                }
                return;
            }

            // Прием запроса на паринг
            ACLMessage pairRequest = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Pair-request"),
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
            
            if (pairRequest != null) {
                if (isBalancing) {
                    ACLMessage refuse = pairRequest.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    send(refuse);
                } else {
                    isBalancing = true;
                    currentPartner = pairRequest.getSender();
                    readyToPair = true;
                    
                    ACLMessage accept = pairRequest.createReply();
                    accept.setPerformative(ACLMessage.AGREE);
                    accept.setOntology("Pair-accept");
                    send(accept);
                    
                    System.out.println("[" + computerName + "] Accepted pairing with " + currentPartner.getLocalName());
                }
                return;
            }

            // Остальные сообщения
            if (readyToPair) {
                ACLMessage taskExchange = receive(MessageTemplate.and(
                        MessageTemplate.MatchOntology("Task-exchange"),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
                
                if (taskExchange != null) {
                    handleTaskExchange(taskExchange);
                    readyToPair = false;
                    return;
                }
                
                ACLMessage exchangeComplete = receive(MessageTemplate.and(
                        MessageTemplate.MatchOntology("Exchange-complete"),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
                
                if (exchangeComplete != null) {
                    System.out.println("[" + computerName + "] Exchange completed.");
                    isBalancing = false;
                    currentPartner = null;
                    readyToPair = false;
                    return;
                }
            }

            block();
        }

        private void startPairBalancing() {
            // Расчет среднего
            double avgTime = computers.values().stream()
                    .filter(Objects::nonNull)
                    .mapToDouble(s -> Double.parseDouble(s.split(" ")[0]))
                    .average()
                    .orElse(0.0);

            // Проверка критерия остановки
            long withinDelta = computers.values().stream()
                    .filter(Objects::nonNull)
                    .mapToDouble(s -> Double.parseDouble(s.split(" ")[0]))
                    .filter(t -> Math.abs(t - avgTime) <= delta)
                    .count();

            // Проверка на изменения
            boolean hasChanged = Math.abs(totalTime - lastTotalTime) > 0.001 || myTasks.size() != lastTaskCount;
            
            if (!hasChanged) {
                unchangedRounds++;
            } else {
                unchangedRounds = 0;
            }

            lastTotalTime = totalTime;
            lastTaskCount = myTasks.size();

            if (withinDelta >= computers.size() || unchangedRounds >= 3) {
                System.out.println("[" + computerName + "] Balancing complete. Avg=" + String.format("%.2f", avgTime) + 
                                 ", My time=" + String.format("%.2f", totalTime) + 
                                 ", Deviation=" + String.format("%.2f", Math.abs(totalTime - avgTime)) +
                                 ", Unchanged rounds=" + unchangedRounds);
                isBalancing = false;
                balancingInitiated = false;
                return;
            }

            // Сортировка компьютеров по загрузке
            List<Map.Entry<AID, String>> sortedComputers = computers.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .sorted(Comparator.comparingDouble(e -> Double.parseDouble(e.getValue().split(" ")[0])))
                    .collect(Collectors.toList());

            // Находим мою позицию
            int myIndex = -1;
            for (int i = 0; i < sortedComputers.size(); i++) {
                if (sortedComputers.get(i).getKey().equals(getAID())) {
                    myIndex = i;
                    break;
                }
            }

            if (myIndex == -1) return;

            // Намерение пары
            int pairIndex = sortedComputers.size() - 1 - myIndex;
            
            // Если я - медиана
            if (sortedComputers.size() % 2 == 1 && myIndex == sortedComputers.size() / 2) {
                System.out.println("[" + computerName + "] I am the median, skipping pairing.");
                isBalancing = false;
                return;
            }

            // Проверка пары
            if (pairIndex == myIndex || pairIndex < 0 || pairIndex >= sortedComputers.size()) {
                isBalancing = false;
                return;
            }

            AID partner = sortedComputers.get(pairIndex).getKey();
            
            // Только менее загруженный инициирует
            if (myIndex < sortedComputers.size() / 2) {
                isBalancing = true;
                currentPartner = partner;
                
                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.setOntology("Pair-request");
                request.setContent(String.valueOf(avgTime));
                request.addReceiver(partner);
                send(request);
                
                System.out.println("[" + computerName + "] Requesting pairing with " + partner.getLocalName());
                
                // Передаю задачу
                performTaskExchange(avgTime);
            }
        }

        private void performTaskExchange(double avgTime) {
            if (currentPartner == null) return;
            
            String partnerData = computers.get(currentPartner);
            if (partnerData == null) return;
            
            double partnerTime = Double.parseDouble(partnerData.split(" ")[0]);
            int partnerCapacity = Integer.parseInt(partnerData.split(" ")[1]);
            
            // Онределяем, кто более загружен
            boolean iAmMoreLoaded = totalTime > partnerTime;
            
            if (iAmMoreLoaded && !myTasks.isEmpty()) {
                // Я более загружен, нужно передать задачу
                Map.Entry<AID, Integer> taskToGive = findBestTaskToGive(avgTime, partnerTime, partnerCapacity);
                
                if (taskToGive != null) {
                    // Предаю задачу
                    ACLMessage exchange = new ACLMessage(ACLMessage.INFORM);
                    exchange.setOntology("Task-exchange");
                    exchange.setContent(taskToGive.getKey().getName() + " " + taskToGive.getValue());
                    exchange.addReceiver(currentPartner);
                    send(exchange);
                    
                    // Удаляю задачу из своего списка
                    myTasks.remove(taskToGive);
                    totalTime -= (double) taskToGive.getValue() / capacity;
                    
                    // Отправляю Remove TaskAgent
                    ACLMessage removeMsg = new ACLMessage(ACLMessage.INFORM);
                    removeMsg.setOntology("Remove");
                    removeMsg.addReceiver(taskToGive.getKey());
                    send(removeMsg);
                    
                    System.out.println("[" + computerName + "] Sent task " + taskToGive.getKey().getLocalName() + 
                                     " to " + currentPartner.getLocalName());
                } else {
                    System.out.println("[" + computerName + "] No suitable task to exchange.");
                }
            } else {
                System.out.println("[" + computerName + "] Partner more loaded or I have no tasks. Waiting.");
            }
        }

        private Map.Entry<AID, Integer> findBestTaskToGive(double avgTime, double partnerTime, int partnerCapacity) {
            Map.Entry<AID, Integer> bestTask = null;
            double bestImprovement = 0;
            
            for (Map.Entry<AID, Integer> task : myTasks) {
                double myNewTime = totalTime - (double) task.getValue() / capacity;
                double partnerNewTime = partnerTime + (double) task.getValue() / partnerCapacity;
                
                double currentMaxDev = Math.max(Math.abs(totalTime - avgTime), Math.abs(partnerTime - avgTime));
                double newMaxDev = Math.max(Math.abs(myNewTime - avgTime), Math.abs(partnerNewTime - avgTime));
                double improvement = currentMaxDev - newMaxDev;
                
                if (improvement > bestImprovement) {
                    bestImprovement = improvement;
                    bestTask = task;
                }
            }
            
            return bestTask;
        }

        private void handleTaskExchange(ACLMessage exchange) {
            String[] parts = exchange.getContent().split(" ");
            String taskName = parts[0];
            int complexity = Integer.parseInt(parts[1]);
            
            // Находим задачу
            AID taskAID = null;
            for (AID aid : tasks.keySet()) {
                if (aid.getName().contains(taskName)) {
                    taskAID = aid;
                    break;
                }
            }
            
            if (taskAID != null) {
                System.out.println("[" + computerName + "] Received task " + taskName + 
                                 " from " + exchange.getSender().getLocalName());
                // Принимаю задачу
                myTasks.add(Map.entry(taskAID, complexity));
                totalTime += (double) complexity / capacity;
                tasks.put(taskAID, true);
            }
            
            // Отправляю оповещение о завершении
            ACLMessage complete = new ACLMessage(ACLMessage.INFORM);
            complete.setOntology("Exchange-complete");
            complete.addReceiver(exchange.getSender());
            send(complete);
        }
    }
}
