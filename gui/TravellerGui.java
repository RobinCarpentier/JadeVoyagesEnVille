package voyagesEnVille.gui;

import jade.gui.GuiEvent;
import voyagesEnVille.agents.TravellerAgent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Journey resarch Gui, communication with TravellerAgent throw GuiEvent
 *
 * @author modif. Emmanuel Adam - LAMIH
 */
@SuppressWarnings("serial")
public class TravellerGui extends JFrame {

    /**
     * Text area
     */
    private final JTextArea jTextArea;


    private final TravellerAgent myAgent;
    private final JLabel lblPrice;
    private JComboBox<String> jListFrom;
    private JComboBox<String> jListTo;
    private JComboBox<String> jListCriteria;
    private JSlider sliderTimeDeparture;

    private String departure;
    private String arrival;
    private int time;

    public TravellerGui(TravellerAgent a) {
        this.setBounds(10, 10, 600, 450);

        myAgent = a;
        if (a != null)
            setTitle(myAgent.getLocalName());

        jTextArea = new JTextArea();
        jTextArea.setBackground(new Color(255, 255, 240));
        jTextArea.setEditable(false);
        jTextArea.setColumns(10);
        jTextArea.setRows(5);
        JScrollPane jScrollPane = new JScrollPane(jTextArea);
        getContentPane().add(BorderLayout.CENTER, jScrollPane);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout());

        JPanel p = new JPanel();
        p.setLayout(new GridLayout(0, 4, 0, 0));
        p.add(new JLabel("From:"));
        p.add(new JLabel("To:"));

        lblPrice = new JLabel("Departure: 6:00");
        p.add(lblPrice);

        p.add(new JLabel("Criteria"));

        // --- Checkbox pour activer/désactiver Ollama ---
        JCheckBox ollamaCheck = new JCheckBox("Activer Ollama");
        ollamaCheck.addActionListener(event -> {
            boolean useOllama = ollamaCheck.isSelected();
            println("[Mode Ollama] " + (useOllama ? "Activé" : "Désactivé"));
        });
        mainPanel.add(ollamaCheck, BorderLayout.NORTH);

        // --- Zone de texte pour la demande en langage naturel ---
        JPanel naturalPanel = new JPanel();
        naturalPanel.setLayout(new BorderLayout());
        JLabel naturalLabel = new JLabel("Demande en langage naturel :");
        JTextField naturalField = new JTextField();
        JButton naturalButton = new JButton("Envoyer");

        naturalButton.addActionListener(event -> {
            String sentence = naturalField.getText().trim();
            if (!sentence.isEmpty()) {
                GuiEvent guiEv = new GuiEvent(this, TravellerAgent.NATURAL_REQUEST);
                guiEv.addParameter(sentence);
                guiEv.addParameter(ollamaCheck.isSelected());
                myAgent.postGuiEvent(guiEv);
            } else {
                JOptionPane.showMessageDialog(this, "Veuillez entrer une phrase.", "Erreur", JOptionPane.ERROR_MESSAGE);
            }
        });

        naturalPanel.add(naturalLabel, BorderLayout.NORTH);
        naturalPanel.add(naturalField, BorderLayout.CENTER);
        naturalPanel.add(naturalButton, BorderLayout.EAST);
        getContentPane().add(naturalPanel, BorderLayout.NORTH);

        JButton addButton = new JButton("Buy");
        addButton.addActionListener(event -> {
            try {
                departure = (String) jListFrom.getSelectedItem();
                arrival = (String) jListTo.getSelectedItem();
                time = sliderTimeDeparture.getValue();
                int hh = sliderTimeDeparture.getValue() / 100;
                int mm = (int) (sliderTimeDeparture.getValue() % 100 / 100d * 60d);
                time = hh * 100 + mm;
                // SEND AN GUI EVENT TO THE AGENT !!!
                GuiEvent guiEv = new GuiEvent(this, TravellerAgent.BUY_TRAVEL);
                guiEv.addParameter(departure);
                guiEv.addParameter(arrival);
                guiEv.addParameter(time);
                guiEv.addParameter(jListCriteria.getSelectedItem());
                guiEv.addParameter(ollamaCheck.isSelected());
                myAgent.postGuiEvent(guiEv);
                // END SEND AN GUI EVENT TO THE AGENT !!!
            } catch (Exception e) {
                JOptionPane.showMessageDialog(TravellerGui.this, "Invalid values. " + e.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        jListFrom = new JComboBox<>(new String[]{"-", "a", "b", "c", "d", "e", "f"});
        jListFrom.setSelectedIndex(0);
        p.add(jListFrom);

        jListTo = new JComboBox<>(new String[]{"-", "a", "b", "c", "d", "e", "f"});
        jListTo.setSelectedIndex(0);
        p.add(jListTo);

        sliderTimeDeparture = new JSlider();
        sliderTimeDeparture.setPreferredSize(new Dimension(100, 10));
        sliderTimeDeparture.setMinimum(600);
        sliderTimeDeparture.setMaximum(2200);
        sliderTimeDeparture.setMajorTickSpacing(100);
        sliderTimeDeparture.setMinorTickSpacing(25);
        sliderTimeDeparture.setSnapToTicks(true);
        sliderTimeDeparture.setPaintTicks(true);
        sliderTimeDeparture.addChangeListener(event -> {
            int hh = sliderTimeDeparture.getValue() / 100;
            int mm = (int) (sliderTimeDeparture.getValue() % 100 / 100d * 60d);
            String smm = (mm < 10) ? ("0" + mm) : String.valueOf(mm);
            lblPrice.setText("Departure: " + hh + ":" + smm);
            lblPrice.repaint();
        });
        p.add(sliderTimeDeparture);

        jListCriteria = new JComboBox<>(new String[]{"-", "cost", "co2", "confort", "duration", "duration-cost"});
        jListCriteria.setSelectedIndex(0);
        p.add(jListCriteria);

        p.add(addButton);
        p.add(new JLabel());
        p.add(new JLabel("Arrival"));

        mainPanel.add(p, BorderLayout.CENTER);

        getContentPane().add(mainPanel, BorderLayout.SOUTH);

        // Make the agent terminate when the user closes
        // the GUI using the button on the upper right corner
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // SEND AN GUI EVENT TO THE AGENT !!!
                GuiEvent guiEv = new GuiEvent(this, TravellerAgent.EXIT);
                myAgent.postGuiEvent(guiEv);
                // END SEND AN GUI EVENT TO THE AGENT !!!
            }
        });

        setResizable(true);
    }

    public static void main(String[] args) {
        TravellerGui test = new TravellerGui(null);
        test.setVisible(true);
    }

    /**
     * add a string to the text area
     */
    public void println(String chaine) {
        String texte = jTextArea.getText();
        texte = texte + chaine + "\n";
        jTextArea.setText(texte);
        jTextArea.setCaretPosition(texte.length());
    }

    public void setColor(Color color) {
        jTextArea.setBackground(color);
    }

}
