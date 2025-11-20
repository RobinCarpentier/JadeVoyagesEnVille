package voyagesEnVille.agents;

import jade.core.AID;
import jade.core.AgentServicesTools;
import jade.core.behaviours.ReceiverBehaviour;
import jade.domain.DFSubscriber;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.gui.GuiAgent;
import jade.gui.GuiEvent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import voyagesEnVille.comportements.ContractNetAchat;
import voyagesEnVille.data.ComposedJourney;
import voyagesEnVille.data.JourneysList;
import voyagesEnVille.gui.TravellerGui;

import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.json.JSONObject;

/**
 * Journey searcher
 *
 * @author Emmanuel ADAM
 */
public class TravellerAgent extends GuiAgent {
    /**
     * code pour ajout de livre par la gui
     */
    public static final int EXIT = 0;
    /**
     * code pour achat de livre par la gui
     */
    public static final int BUY_TRAVEL = 1;

    public static final int NATURAL_REQUEST = 2;

    /**
     * liste des vendeurs
     */
    private ArrayList<AID> vendeurs;

    /**
     * catalog received by the sellers
     */
    private JourneysList catalogs;

    /**
     * delay in minute between two rides in a junction (in minutes)
     * */
    int delay = 90;

    /**
     * the journey chosen by the agent
     */
    private ComposedJourney myJourney;

    /**
     * topic from which the alert will be received
     */
    private AID topic;

    /**
     * gui
     */
    private TravellerGui window;

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL = "granite3.3:2b";

    // Fonction utilitaire : échappe les caractères spéciaux pour JSON
    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public static String generateResponse(String prompt) {
        StringBuilder finalResponse = new StringBuilder();

        try {
            String safePrompt = escapeJson(prompt);
            String json = String.format("{\"model\": \"%s\", \"prompt\": \"%s\", \"stream\": true}", MODEL, safePrompt);

            URL url = new URL(OLLAMA_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(json.getBytes());
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int start = line.indexOf("\"response\":\"");
                    if (start != -1) {
                        start += 12;
                        StringBuilder sb = new StringBuilder();
                        boolean escape = false;

                        for (int i = start; i < line.length(); i++) {
                            char c = line.charAt(i);

                            if (escape) {
                                if (c == 'n') sb.append('\n');
                                else if (c == 'r') sb.append('\r');
                                else if (c == 't') sb.append('\t');
                                else sb.append(c);
                                escape = false;
                            } else if (c == '\\') {
                                escape = true;
                            } else if (c == '"') {
                                break;
                            } else {
                                sb.append(c);
                            }
                        }

                        finalResponse.append(sb.toString());
                    }
                }
            }

            return finalResponse.toString().trim();

        } catch (Exception e) {
            return "[Erreur Ollama : " + e.getMessage() + "]";
        }
    }

    private void parlerOllama(String message) {
        String prompt = String.format("""
            Rephrase this message in a natural, polite, and fluent sentence in French, 
            as if it were a travel assistant speaking to the user:
            "%s"
            """, message);
        String response = generateResponse(prompt);
        println(response);
    }

    /**
     * Initialisation de l'agent
     */
    @Override
    protected void setup() {
        this.window = new TravellerGui(this);
        window.setColor(Color.cyan);
        window.println("Hello! AgentAcheteurCN " + this.getLocalName() + " est pret. ");
        window.setVisible(true);

        vendeurs = new ArrayList<>();
        detectAgences();

        topic = AgentServicesTools.generateTopicAID(this, "TRAFFIC NEWS");
        //ecoute des messages radio
        addBehaviour(new ReceiverBehaviour(this, -1, MessageTemplate.MatchTopic(topic), true, (a, m)->{
            println("Message recu sur le topic " + topic.getLocalName() + ". Contenu " + m.getContent()
                    + " emis par :  " + m.getSender().getLocalName());
        }));

    }


    /**
     * ecoute des evenement de type enregistrement en tant qu'agence aupres des pages jaunes
     */
    private void detectAgences() {
        var model = AgentServicesTools.createAgentDescription("travel agency", "seller");
        vendeurs = new ArrayList<>();

        //souscription au service des pages jaunes pour recevoir une alerte en cas mouvement sur le service travel agency'seller
        addBehaviour(new DFSubscriber(this, model) {
            @Override
            public void onRegister(DFAgentDescription dfd) {
                vendeurs.add(dfd.getName());
                window.println(dfd.getName().getLocalName() + " s'est inscrit en tant qu'agence : " + model.getAllServices().get(0));
            }

            @Override
            public void onDeregister(DFAgentDescription dfd) {
                vendeurs.remove(dfd.getName());
                window.println(dfd.getName().getLocalName() + " s'est desinscrit de  : " + model.getAllServices().get(0));
            }

        });

    }

    /**
     * compute a composed journey from a departure to an arrival point
     * @param from       departure point
     * @param to         arrival point
     * @param departure  desired departure time (in hhmm)
     * @param preference preference for the choice of the journey (cost, confort, duration, duration-cost)
     * */
    public void computeComposedJourney(final String from, final String to, final int departure,
                                       final String preference, boolean ollama) {
        final List<ComposedJourney> journeys = new ArrayList<>();
        //recherche de trajets ac tps d'attentes entre via = 60mn
        final boolean result = catalogs.findIndirectJourney(from, to, departure, 60, new ArrayList<>(),
                new ArrayList<>(), journeys);

        if (!result) {
            if (!ollama) {
                println("no journey found !!!");
            }
            else {
                parlerOllama("no journey found !!!");
            }
            this.myJourney = null;
        }
        if (result) {
            //oter les voyages demarrant trop tard (1h30 apres la date de depart souhaitee)
            journeys.removeIf(j -> j.getJourneys().getFirst().getDepartureDate() - departure > delay);
            switch (preference) {
                case "duration" -> {
                    journeys.sort(Comparator.comparingDouble(ComposedJourney::getDuration));                }
                case "confort" -> journeys.sort(Comparator.comparingInt(ComposedJourney::getConfort).reversed());
                case "cost" -> journeys.sort(Comparator.comparingDouble(ComposedJourney::getCost));
                case "duration-cost" ->
                    journeys.sort((j1, j2) -> {
                    var difDuration = j1.getDuration() - j2.getDuration() / Math.max(j2.getDuration(),j1.getDuration());
                    var difCost = j1.getCost() - j2.getCost() / Math.max(j2.getCost(),j1.getCost());
                    return (int)(10*(difDuration + difCost));});
                case "co2" -> journeys.sort(Comparator.comparingDouble(ComposedJourney::getCo2));
                default -> journeys.sort(Comparator.comparingDouble(ComposedJourney::getCost));
            }
            myJourney = journeys.getFirst();
            if (!ollama) {
                println("I choose this journey : " + myJourney);
            }
            else {
                parlerOllama("I choose this journey : " + myJourney);
            }
        }
    }

    /**
     * get event from the GUI
     */
    @Override
    protected void onGuiEvent(final GuiEvent eventFromGui) {
        if (eventFromGui.getType() == TravellerAgent.EXIT) {
            doDelete();
        }
        if (eventFromGui.getType() == TravellerAgent.BUY_TRAVEL) {
            boolean ollama = (boolean) eventFromGui.getParameter(4);

            addBehaviour(new ContractNetAchat(this, new ACLMessage(ACLMessage.CFP),
                    (String) eventFromGui.getParameter(0), (String) eventFromGui.getParameter(1),
                    (Integer) eventFromGui.getParameter(2), (String) eventFromGui.getParameter(3), ollama));
        }
        if (eventFromGui.getType() == TravellerAgent.NATURAL_REQUEST) {
            String sentence = (String) eventFromGui.getParameter(0);
            boolean ollama = (boolean) eventFromGui.getParameter(1);

            addBehaviour(new ContractNetAchat(this, new ACLMessage(ACLMessage.CFP), sentence, ollama));
        }
    }

    // 'Nettoyage' de l'agent
    @Override
    protected void takeDown() {
        if(window!=null) {
            window.dispose();
            System.out.println(getLocalName() +   ">>> I leave the platform. ");
        }
    }

    ///// SETTERS AND GETTERS

    /**
     * @return agent gui
     */
    public TravellerGui getWindow() {
        return window;
    }


    /**
     * @return the vendeurs
     */
    public List<AID> getVendeurs() {
        return (ArrayList<AID>) vendeurs.clone();
    }


    /**
     * print a message on the window lined to the agent
     *
     * @param msg text to display in th window
     */
    public void println(final String msg) {
        window.println(msg);
    }

    /**
     * set the list of journeys
     */
    public void setCatalogs(final JourneysList catalogs) {
        this.catalogs = catalogs;
    }


    public ComposedJourney getMyJourney() {
        return myJourney;
    }

}
