package voyagesEnVille.comportements;

import voyagesEnVille.gui.AgenceGui;
import jade.core.Agent;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.proto.ContractNetResponder;
import voyagesEnVille.agents.AgenceAgent;
import voyagesEnVille.data.Journey;
import voyagesEnVille.data.JourneysList;

import java.io.IOException;
import java.util.ArrayList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Journeys Seller Behaviour by contract net
 *
 * @author revised by Emmanuel ADAM
 * @version 191017
 */
@SuppressWarnings("serial")
public class ContractNetVente extends ContractNetResponder {

    /**
     * catalog of the proposed journeys
     */
    private final JourneysList catalog;


    /**
     * agent gui
     */
    private final AgenceGui window;

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

    /**
     * Initialisation du contract net
     *
     * @param agent    agent agence lie
     * @param template modele de message a attendre
     * @param _catalog catalogue des voyages
     */
    public ContractNetVente(Agent agent, MessageTemplate template, JourneysList _catalog) {
        super(agent, template);
        var monAgent = (AgenceAgent) agent;
        window = monAgent.getWindow();
        catalog = _catalog;
    }

    /**
     * methode lancee a la reception d'un appel d'offre
     *
     * @param cfp l'appel recu
     * @throws NotUnderstoodException si le message n'est pas compris
     * @throws RefuseException        s'il n'y a pas de trajet en catalogue
     * @see ContractNetResponder#handleCfp(ACLMessage)
     */
    protected ACLMessage handleCfp(ACLMessage cfp) throws NotUnderstoodException, RefuseException {
        window.println("Agent " + myAgent.getLocalName() + ": CFP recu de " + cfp.getSender().getLocalName());
        if (catalog.isEmpty()) throw new RefuseException("no journey !");
        var propose = cfp.createReply();
        propose.setPerformative(ACLMessage.PROPOSE);
        try {
            propose.setContentObject(catalog);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return propose;
    }


    /**
     * methode lancee suite a la reception d'une acceptation de l'offre par l'acheteur
     *
     * @param cfp     l'appel a proposition initial
     * @param propose la proposition retourne par l'agent
     * @param accept  le message d'acceptation de l'offre
     * @return le message de confirmation de la vente
     * @throws FailureException si le livre n'est plus disponible (si la transaction echoue)
     * @see ContractNetResponder#handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
     */
    protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {
        ACLMessage inform = accept.createReply();
        inform.setPerformative(ACLMessage.INFORM);
        window.println(" RECU UN ACCORD DE " + accept.getSender().getLocalName() + " !!!");
        ArrayList<Journey> liste = null;
        try {
            liste = (ArrayList<Journey>) accept.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
        if (liste != null) {
            window.println("Il veut ");
            liste.forEach(j -> window.println(j.toString()));
            window.println("  !!!!");
            inform.setContent("ok pour ces " + liste.size() + " tickets...");
            //for each ticket bought, remove 1 place
            liste.forEach(this::removeTicket);
        }
        return inform;
    }


    /**get in the catalog the journey corresponding to j and remove one place*/
    private void removeTicket(Journey j) {
        ArrayList<Journey> list = catalog.getJourneysFrom(j.getStart());
        String prompt;
        String response;

        Boolean ollama = false;
    
        if(list == null || list.isEmpty()) {
            if(!ollama) {
                System.out.println("Aucun voyage trouvé depuis : " + j.getStart());
            }
            else {
                prompt = """
                        Write a polite and natural sentence to tell a user that there are no trips in the catalog
                        """;
                response = generateResponse(prompt);
                System.out.println(response+" : "+j.getStart());
            }
            return;
        }
    
        if(!ollama) {
            System.out.println("Tentative de suppression pour : " + j);
        }
        else {
            prompt = """
                        Write a polite and natural sentence to tell a user that they are going to collect a ticket from the trip
                        """;
            response = generateResponse(prompt);
            System.out.println(response+" : "+j);
        }
    
        boolean found = false;
    
        for(Journey journey : list) {
            if(!ollama) {
                System.out.println("Comparaison avec catalogue : " + journey);
            }
            else{
                prompt = """
                        Write a polite and natural sentence to tell a user that they are going to collect a ticket from the trip
                        """;
                response = generateResponse(prompt);
                System.out.println(response+" : "+journey);
            }
    
            if(journey.getStop().equals(j.getStop()) &&
               (journey.getDepartureDate() == j.getDepartureDate()) && (journey.getMeans().equals(j.getMeans()))) {
                
                int placesAvant = journey.getPlaces();
                journey.setPlaces(placesAvant - 1);
                if(!ollama) {
                    System.out.println("  => Correspondance trouvée ! Places avant : " + placesAvant + ", après : " + journey.getPlaces());
                }
                else{
                    prompt = "Rédige une phrase polie et naturelle pour dire à un utilisateur que la correspondance de voyage a été trouvée et que le nombre de places place de "+placesAvant+"à"+journey.getPlaces();
                    response = generateResponse(prompt);
                    System.out.println(response);
                }
                found = true;
                // On ajoute un ticket pour le voyage "inverse" pour les Bike libres-service
                if(journey.getMeans().equals("bike")) {
                    addTicket(j);
                }
            }
        }
    
        if(!found) {
            if(!ollama) {
                System.out.println("Aucune correspondance trouvée pour ce voyage !");
            }
            else{
                prompt = """
                        Write a polite and natural sentence to tell a user that no trips match their search
                        """;
                response = generateResponse(prompt);
                System.out.println(response);
            }
        }
    }

    /**get in the catalog the journey corresponding to j and add one place*/
    private void addTicket(Journey j) {
        ArrayList<Journey> list = catalog.getJourneysFrom(j.getStop());
        String prompt;
        String response;

        Boolean ollama = false;
    
        if(list == null || list.isEmpty()) {
            if(!ollama) {
                System.out.println("Aucun voyage trouvé depuis : " + j.getStop());
            }
            else{
                prompt = """
                        Write a polite and natural sentence to tell a user that there are no trips in the catalog
                        """;
                response = generateResponse(prompt);
                System.out.println(response+" : "+j.getStart());
            }
            return;
        }
    
        boolean found = false;
    
        for(Journey journey : list) {
            if(!ollama) {
                System.out.println("Comparaison avec catalogue : " + journey);
            }
            else{
                prompt = """
                    Write a polite and natural sentence to tell a user that they are going to collect a ticket from the trip
                    """;
                response = generateResponse(prompt);
                System.out.println(response+" : "+journey);
            }
    
            if(journey.getStop().equals(j.getStart()) &&
            (journey.getDepartureDate() == j.getArrivalDate()) && (journey.getMeans().equals(j.getMeans()))) {
                
                int placesAvant = journey.getPlaces();
                journey.setPlaces(placesAvant + 1);
                if(!ollama) {
                    System.out.println("  => Correspondance trouvée ! Places avant : " + placesAvant + ", après : " + journey.getPlaces());
                }
                else{
                    prompt = "Rédige une phrase polie et naturelle pour dire à un utilisateur que la correspondance de voyage a été trouvée et que le nombre de places place de "+placesAvant+"à"+journey.getPlaces();
                    response = generateResponse(prompt);
                    System.out.println(response);
                }
                found = true;
            }
        }
    
        if(!found) {
            if(!ollama) {
                System.out.println("Aucune correspondance trouvée pour ce voyage !");
            }
            else{
                prompt = """
                        Write a polite and natural sentence to tell a user that no trips match their search
                        """;
                response = generateResponse(prompt);
                System.out.println(response);
            }
        }
    }

    /**
     * methode lancee suite a la reception d'un refus de l'offre par l'acheteur
     *
     * @param cfp     l'appel a proposition initial
     * @param propose la proposition retourne par l'agent
     * @param reject  le message de refus de l'offre
     * @see ContractNetResponder#handleRejectProposal(ACLMessage, ACLMessage, ACLMessage)
     */
    protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
        window.println("Agent " + reject.getSender().getLocalName() + " a rejete la proposition pour " + reject.getContent());
    }
} 
