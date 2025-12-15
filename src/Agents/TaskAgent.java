package Agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TaskAgent extends Agent {
    // Define variables
    private int complexity;
    private AID myComputer;
    private Map<AID, Double> computers = new HashMap<>();

    protected void setup() {
        // Filling variables
        Object[] args = getArguments();
        if (args != null && args.length > 0)
            complexity = Integer.parseInt((String) args[0]);
        else complexity = 100;

        // Register in yellow pages
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("tasks");
        sd.setName("JADE-Task");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        }
        catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Add Behaviours
        addBehaviour(new SearchComputersBehaviour());
        addBehaviour(new AnswersGetBehaviour());
        addBehaviour(new ComputerConfirmationBehaviour());
        addBehaviour(new RemoveBehaviour());
        addBehaviour(new AnswerBehaviour());

        System.out.println("TaskAgent " + getAID().getName() + " is ready.");
    }

    protected void takeDown(){
        // Deregister in yellow pages
        try {
            DFService.deregister(this);
        }
        catch (FIPAException fe) {
            fe.printStackTrace();
        }

        System.out.println("TaskAgent " + getAID().getName() + " is terminated.");
    }

    private  class SearchComputersBehaviour extends OneShotBehaviour {
        public void action() {
            // Search in yellow pages
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("task-executing");
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                for (DFAgentDescription res : result) computers.put(res.getName(), null);
            }
            catch (FIPAException fe) {
                fe.printStackTrace();
            }

            // Send task to computers
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (AID computer: computers.keySet()) cfp.addReceiver(computer);
            cfp.setContent(String.valueOf(complexity));
            send(cfp);
        }
    }

    private class AnswersGetBehaviour extends CyclicBehaviour {
        public void action() {
            // Getting execution times from computers
            ACLMessage reply = receive(MessageTemplate.MatchPerformative(ACLMessage.PROPOSE));
            if (reply != null && myComputer == null)
                computers.put(reply.getSender(), Double.parseDouble(reply.getContent()));
            else block();
        }
    }

    private class ComputerConfirmationBehaviour extends CyclicBehaviour {
        public void action() {
            if (!computers.containsValue(null) && myComputer == null) {
                // Computer set
                double bestTime = Double.MAX_VALUE;
                AID bestComputer = null;
                for (Map.Entry<AID, Double> entry : computers.entrySet()) {
                    if (entry.getValue() < bestTime) {
                        bestTime = entry.getValue();
                        bestComputer = entry.getKey();
                    }
                }
                myComputer = bestComputer;

                // Answers to computers
                ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                for (AID computer : computers.keySet()) {
                    if (Objects.equals(computer.getName(), myComputer.getName())) {
                        accept.addReceiver(computer);
                        accept.setContent(String.valueOf(complexity));
                    }
                    else reject.addReceiver(computer);
                }
                send(accept);
                send(reject);
            }
        }
    }

    private class RemoveBehaviour extends CyclicBehaviour {
        public void action() {
            // Getting info of remove task from computer
            ACLMessage info = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Remove"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            if (info != null) {
                myComputer = null;
                addBehaviour(new SearchComputersBehaviour());
            }
            else block();
        }
    }

    private class AnswerBehaviour extends CyclicBehaviour {
        public void action() {
            // Answer to question
            ACLMessage question = receive(MessageTemplate.and(
                    MessageTemplate.MatchOntology("Question"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
            if (question != null) {
                ACLMessage answer = new ACLMessage(ACLMessage.INFORM);
                answer.setOntology("Answer");
                boolean ans = myComputer != null;
                answer.setContent(String.valueOf(ans));
                answer.addReceiver(question.getSender());
                send(answer);
            }
            else block();
        }
    }
}
