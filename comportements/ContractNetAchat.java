package voyagesEnVille.comportements;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;
import jade.proto.ContractNetInitiator;
import voyagesEnVille.agents.TravellerAgent;
import voyagesEnVille.data.Journey;
import voyagesEnVille.data.JourneysList;
import voyagesEnVille.gui.TravellerGui;

import java.io.IOException;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;


/**
 * Journey Buyer Behaviour by contract net
 *
 * @author revised by Emmanuel ADAM
 * @version 191017
 */
public class ContractNetAchat extends ContractNetInitiator {

    private final String from;
    private final String to;
    private final int departure;
    private final String preference;

    /**
     * agent gui
     */
    private final TravellerGui window;

    /**
     * acheteur lie a ce comportement
     */
    private final TravellerAgent monAgent;

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL = "granite3.3:2b";

    private boolean useOllama = false;

    public void setUseOllama(boolean value) {
        this.useOllama = value;
    }

    public boolean isUseOllama() {
        return useOllama;
    }

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

    public static JSONObject interpretUserRequest(String userInput) {
        String prompt = """
            You are a travel assistant for an intelligent travel planning system.
            Your goal is to extract structured information from the user's request.
            
            The user can ask in any language, but you must always return a valid JSON object.
            
            Only the cities a, b, c, d, e, and f are valid (not in upper case!). If the user mentions another city, replace it with 'a'.
            
            Return ONLY a valid JSON object with the following structure:
            {
              "from": "departure city (a-f)",
              "to": "destination city (a-f)",
              "departure": integer (desired departure time in hhmm, rounded to the nearest 5 minutes, e.g., 832 → 835, 1500 → 1500),
              "preference": "cost" | "duration" | "confort" | "duration-cost" | "co2"
            }
            
            - If the departure time is not given, set it to 800 (8:00 a.m.).
            - If the preference is not clear, default to "cost".
            - Do not include any text outside the JSON.
            
            User request: "%s"
            """.formatted(userInput);
    
        try {
            String response = generateResponse(prompt).trim();

            System.out.println(response);
    
            if (response.startsWith("```")) {
                response = response.replaceAll("```(json)?", "").trim();
            }
    
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start != -1 && end != -1 && end > start) {
                response = response.substring(start, end + 1);
            }
            System.out.println(response);
    
            return new JSONObject(response);
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
            return null;
        }
    }

    private void parlerOllama(String message) {
        String prompt = String.format("""
            Rephrase this message in a natural, polite, and fluent sentence in French, 
            as if it were a travel assistant speaking to the user:
            "%s"
            """, message);
        String response = generateResponse(prompt);
        monAgent.println(response);
    }

    /**
     * initialisation
     *
     * @param agent       agent initiator
     * @param msg         initial message to send
     * @param _from       origine city
     * @param _to         destination city
     * @param _departure  date of departure
     * @param _preference criteria (cost, duration, ...)
     */
    public ContractNetAchat(Agent agent, ACLMessage msg, final String _from, final String _to, final int _departure, final String _preference, boolean ollama) {
        super(agent, msg);
        from = _from;
        to = _to;
        departure = _departure;
        preference = _preference;
        monAgent = (TravellerAgent) agent;
        useOllama = ollama;
        window = monAgent.getWindow();
        // définition du prococole
        msg.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        // Réponse plus tard dans 1 sec
        msg.setReplyByDate(new Date(System.currentTimeMillis() + 1000));
        List<AID> vendeurs = monAgent.getVendeurs();
        vendeurs.forEach(msg::addReceiver);
        // relancer le comportement pour fixer la date de remise au plus tard, les destinataires, ...
        this.reset(msg);
    }

    public ContractNetAchat(Agent agent, ACLMessage msg, final String userSentence, boolean ollama) {
        super(agent, msg);
        monAgent = (TravellerAgent) agent;
        useOllama = ollama;
        window = monAgent.getWindow();
    
        window.println("Analyse de la demande utilisateur : " + userSentence);
    
        JSONObject parsed = interpretUserRequest(userSentence);
        
        if (parsed != null) {
            from = parsed.optString("from", "A");
            to = parsed.optString("to", "B");
            departure = parsed.has("departure") ? parsed.optInt("departure") : 800;
            preference = parsed.optString("preference", "cost").equals("comfort") ? "confort" : parsed.optString("preference", "cost");
        } else {
            from = "A";
            to = "B";
            departure = 800;
            preference = "cost";
        }

        window.println("Demande interprétée :");
        window.println(" - Départ : " + from);
        window.println(" - Destination : " + to);
        window.println(" - Heure de départ : " + departure);
        window.println(" - Critère de choix : " + preference);
    
        msg.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        msg.setReplyByDate(new Date(System.currentTimeMillis() + 1000));
        List<AID> vendeurs = monAgent.getVendeurs();
        vendeurs.forEach(msg::addReceiver);
        this.reset(msg);
    }    
    

    /**
     * methode lancee a la reception de chaque refus
     *
     * @param refuse refus recu
     */
    @Override
    protected void handleRefuse(ACLMessage refuse) {
        window.println("Agent " + refuse.getSender().getLocalName() + " refuse");
    }

    /**
     * methode lancee a la reception d'un message d'erreur (impossibilite de poursuivre la vente)
     *
     * @param failure erreur recue
     */
    @Override
    protected void handleFailure(ACLMessage failure) {
        if (failure.getSender().equals(myAgent.getAMS())) {
            // ERREUR : le destinataire n'existe pas
            window.println("Le destinataire n'existe pas...");
        } else
            window.println("Agent " + failure.getSender().getLocalName() + " a echoue");
    }

    /**
     * methode lancée si toutes les reponses sont arrivées ou si le temps est écoulé<br>
     * accepte la meilleure offre, calcul basé sur la notoriété la plus haute et le prix le plus bas à part égale ici<br>
     * n'accepte pas l'offre si dépasse le prix max fixé par l'acheteur
     *
     * @param responses   reponses recues
     * @param acceptances vecteur des messages à transmettre en retour aux réponses reçues
     * @see ContractNetInitiator#handleAllResponses(List, List)
     */
    @Override
    protected void handleAllResponses(List<ACLMessage> responses, List<ACLMessage> acceptances) {
        //catalog of journeys built from answers
        var catalogs = new JourneysList();
        //map <name to the agent (agence), Msg built to answer to it>
        Map<String, ACLMessage> reponses = new HashMap<>();
        for (ACLMessage ans : responses) {
            if (ans.getPerformative() == ACLMessage.PROPOSE) {
                JourneysList receivedCatalog = null;
                try {
                    receivedCatalog = (JourneysList) ans.getContentObject();
                } catch (UnreadableException e) {
                    e.printStackTrace();
                }
                if (receivedCatalog != null) {
                    catalogs.addJourneys(receivedCatalog);
                    if (!isUseOllama()) {
                        monAgent.println("reçu de " + ans.getSender().getLocalName() + " : ");
                    }
                    else {
                        parlerOllama("Voici le catalogue reçu de " + ans.getSender().getLocalName() + " : ");
                    }
                    monAgent.println(receivedCatalog.toString());
                }

            }
            var reply = ans.createReply();
            reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
            acceptances.add(reply);
            reponses.put(ans.getSender().getLocalName(), reply);
        }
        monAgent.setCatalogs(catalogs);
        if (!isUseOllama()) {
            monAgent.println("j'ai bien recu les catalogues : ");
        }
        else {
            parlerOllama("j'ai bien recu les catalogues : ");
        }
        monAgent.println(catalogs.toString());
        if (!isUseOllama()) {
            monAgent.println("je fais mon choix...");
        }
        else {
            parlerOllama("je fais mon choix...");
        }
        monAgent.computeComposedJourney(from, to, departure, preference, isUseOllama());
        //map <name to the agent (agence), list of journeys to buy to it>
        Map<String, ArrayList<Journey>> voyagesAAcheter = new HashMap<>();
        var journey = monAgent.getMyJourney();
        journey.getJourneys().forEach(j ->
                voyagesAAcheter.compute(j.getProposedBy(),
                        (agence, list) -> {
                            if (list == null) list = new ArrayList<>();
                            list.add(j);
                            return list;
                        }));
        voyagesAAcheter.forEach((agence, journeys) -> {
            var msg = reponses.get(agence);
            msg.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            try {
                msg.setContentObject(journeys);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * methode lancee a la reception d'un message d'information (vente confirmee)
     *
     * @param inform message recu
     */
    @Override
    protected void handleInform(ACLMessage inform) {
        window.println("Agent " + inform.getSender().getLocalName() + " : " + inform.getContent());
    }
}
