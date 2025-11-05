import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CfValidator {

    // Mappa mese CF -> mese numerico (A=1, ..., T=12)
    private static final Map<Character, Integer> MONTH_MAP = buildMonthMap();

    // Omocodia: cifra -> lettera e inversa (L->0, M->1, ..., V->9)
    private static final Map<Character, Character> OMO_NUM_TO_LET = buildOmoNumToLet();
    private static final Map<Character, Character> OMO_LET_TO_NUM = invert(OMO_NUM_TO_LET);

    // Tabelle per carattere di controllo (dispari/parì, 1-based)
    private static final Map<Character, Integer> ODD = buildOddMap();
    private static final Map<Character, Integer> EVEN = buildEvenMap();
    private static final char[] CHECK_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    // Pattern CF formale: 6 lettere, 2 cifre/omocodia (anno), 1 lettera mese, 2 cifre/omocodia giorno, 4 alfanum catastale, 1 lettera check
    private static final Pattern CF_BASE = Pattern.compile("^[A-Z]{6}[0-9L-V]{2}[A-Z][0-9L-V]{2}[A-Z0-9]{4}[A-Z]$");

    /**
     * Entry-point dell'applicazione: carica dati, acquisisce input utente,
     * esegue la validazione e mostra risultati dettagliati.
     */
    public static void main(String[] args) throws Exception {
        String cfInput;
        if (args.length > 0) {
            cfInput = args[0].trim();
        } else {
            System.out.print("Inserisci codice fiscale: ");
            cfInput = new BufferedReader(new InputStreamReader(System.in)).readLine().trim();
        }
        String cf = cfInput.toUpperCase(Locale.ITALY);

        // Carica cod_fisco->comune dal JSON, più il set keySet per validazione
        Map<String, String> catastaliMap = loadCatastaliMap("italy_cities.json");
        Set<String> catastali = catastaliMap.keySet();

        ValidationResult res = validateCodiceFiscale(cf, catastali, catastaliMap);

        System.out.println("Codice: " + cf);
        System.out.println("Valido: " + res.valid);
        for (String msg : res.messages) System.out.println("- " + msg);

        System.exit(res.valid ? 0 : 1);
    }

    /**
     * Carica il file JSON restituisce la mappa cod_fisco->comune.
     * Usata sia per la validazione del codice catastale sia per stampare il nome del comune.
     */
    private static Map<String, String> loadCatastaliMap(String path) throws IOException {
        String content = safeReadString(path);
        Map<String, String> map = new HashMap<>();
        Pattern p = Pattern.compile(
            "\"cod_fisco\"\\s*:\\s*\"([A-Z0-9]{4})\".*?\"comune\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(content);
        while (m.find()) {
            map.put(m.group(1).toUpperCase(Locale.ITALY), m.group(2));
        }
        return map;
    }

    /**
     * Legge il contenuto di un file in stringa UTF-8 (compatibile JDK8+).
     * Usato per caricare il JSON dei comuni.
     */
    private static String safeReadString(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Metodo principale di validazione: controlla sintassi, mese, giorno, catastale, carattere di controllo.
     * Fornisce messaggi esplicativi e, se valido, mostra anche il nome del comune.
     */
    private static ValidationResult validateCodiceFiscale(
            String cf, Set<String> catastali, Map<String, String> catastaliMap) {
        List<String> msgs = new ArrayList<>();

        // Controllo lunghezza
        if (cf == null || cf.length() != 16) {
            msgs.add("Lunghezza diversa da 16.");
            return new ValidationResult(false, msgs);
        }

        // Pattern base
        if (!CF_BASE.matcher(cf).matches()) {
            msgs.add("Formato generale non valido (pattern).");
        } else {
            msgs.add("Formato generale conforme.");
        }

        char meseChar = cf.charAt(8);
        String giornoOm = cf.substring(9, 11);
        String catastaleRaw = cf.substring(11, 15);
        char check = cf.charAt(15);

        // Verifica codice mese
        if (!MONTH_MAP.containsKey(meseChar)) {
            msgs.add("Lettera mese non valida: " + meseChar);
        } else {
            msgs.add("Mese valido: " + meseChar + " -> " + MONTH_MAP.get(meseChar));
        }

        // Gestione giorno/sesso, usando omocodia (L–V)
        String giornoDigits = deomocode(giornoOm);
        Integer giorno = tryParseInt(giornoDigits);
        if (giorno == null) {
            msgs.add("Giorno non numerico dopo normalizzazione omocodia: " + giornoDigits);
        } else if (giorno >= 1 && giorno <= 31) {
            msgs.add("Giorno valido (maschio): " + giorno);
        } else if (giorno >= 41 && giorno <= 71) {
            msgs.add("Giorno valido (femmina): " + (giorno - 40));
        } else {
            msgs.add("Giorno fuori range valido: " + giorno);
        }

        // Normalizza le ultime 3 cifre del catastale per gestire l'omocodia
        char catFirst = catastaleRaw.charAt(0);
        String catLast3Norm = deomocode(catastaleRaw.substring(1));
        String catastaleNorm = ("" + catFirst + catLast3Norm).toUpperCase(Locale.ITALY);

        // Controllo e stampa del comune associato al catastale
        if (catFirst == 'Z') {
            msgs.add("Codice catastale estero (Z***): controllo sul dataset comuni italiani non applicato.");
        } else if (catastali.contains(catastaleNorm)) {
            String comune = catastaliMap.get(catastaleNorm);
            msgs.add("Codice catastale valido nel dataset: raw=" + catastaleRaw +
                     ", normalizzato=" + catastaleNorm + ", comune=" + comune);
        } else {
            msgs.add("Codice catastale NON trovato nel dataset: raw=" + catastaleRaw + ", normalizzato=" + catastaleNorm);
        }

        // Validazione carattere di controllo
        char expected = computeCheck(cf.substring(0, 15));
        if (expected == check) {
            msgs.add("Carattere di controllo corretto: " + check);
        } else {
            msgs.add("Carattere di controllo errato: atteso " + expected + " ma trovato " + check);
        }

        boolean ok =
                CF_BASE.matcher(cf).matches() &&
                MONTH_MAP.containsKey(meseChar) &&
                giorno != null &&
                ((giorno >= 1 && giorno <= 31) || (giorno >= 41 && giorno <= 71)) &&
                (catFirst == 'Z' || catastali.contains(catastaleNorm)) &&
                expected == check;

        return new ValidationResult(ok, msgs);
    }

    /**
     * Converte eventuali lettere omocodiche (L–V) in numeri (0–9), lasciando intatto il resto.
     * Serve per normalizzare giorno, codice catastale ecc.
     */
    private static String deomocode(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            Character mapped = OMO_LET_TO_NUM.get(Character.toUpperCase(ch));
            b.append(mapped != null ? mapped : ch);
        }
        return b.toString();
    }

    /**
     * Calcola il carattere di controllo (sedicesima posizione) tramite le tabelle officiali, su base dispari/parì e modulo 26.
     */
    private static char computeCheck(String first15) {
        int sum = 0;
        for (int i = 0; i < first15.length(); i++) {
            char ch = Character.toUpperCase(first15.charAt(i));
            int pos1 = i + 1; // 1-based
            Integer val = ((pos1 % 2) == 1) ? ODD.get(ch) : EVEN.get(ch);
            if (val == null) return '?';
            sum += val;
        }
        int mod = sum % 26;
        return CHECK_CHARS[mod];
    }

    /**
     * Tenta di convertire una stringa in Integer, torna null in caso di errore.
     * Usato per la validazione del giorno.
     */
    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    /**
     * Costruisce la mappa dei mesi (lettera CF -> numero).
     */
    private static Map<Character, Integer> buildMonthMap() {
        Map<Character, Integer> m = new HashMap<>();
        m.put('A', 1); m.put('B', 2); m.put('C', 3); m.put('D', 4); m.put('E', 5);
        m.put('H', 6); m.put('L', 7); m.put('M', 8); m.put('P', 9); m.put('R', 10);
        m.put('S', 11); m.put('T', 12);
        return m;
    }

    /**
     * Costruisce la mappa omocodia cifra->lettera (usata per normalizzare giorni e catastali).
     */
    private static Map<Character, Character> buildOmoNumToLet() {
        Map<Character, Character> m = new HashMap<>();
        m.put('0','L'); m.put('1','M'); m.put('2','N'); m.put('3','P'); m.put('4','Q');
        m.put('5','R'); m.put('6','S'); m.put('7','T'); m.put('8','U'); m.put('9','V');
        return m;
    }

    /**
     * Costruisce la mappa dei valori dispari per il calcolo del carattere di controllo.
     */
    private static Map<Character, Integer> buildOddMap() {
        Map<Character, Integer> m = new HashMap<>();
        m.put('0',1); m.put('1',0); m.put('2',5); m.put('3',7); m.put('4',9);
        m.put('5',13); m.put('6',15); m.put('7',17); m.put('8',19); m.put('9',21);
        m.put('A',1); m.put('B',0); m.put('C',5); m.put('D',7); m.put('E',9);
        m.put('F',13); m.put('G',15); m.put('H',17); m.put('I',19); m.put('J',21);
        m.put('K',2); m.put('L',4); m.put('M',18); m.put('N',20); m.put('O',11);
        m.put('P',3); m.put('Q',6); m.put('R',8); m.put('S',12); m.put('T',14);
        m.put('U',16); m.put('V',10); m.put('W',22); m.put('X',25); m.put('Y',24);
        m.put('Z',23);
        return m;
    }

    /**
     * Costruisce la mappa dei valori pari per il calcolo del carattere di controllo.
     */
    private static Map<Character, Integer> buildEvenMap() {
        Map<Character, Integer> m = new HashMap<>();
        m.put('0',0); m.put('1',1); m.put('2',2); m.put('3',3); m.put('4',4);
        m.put('5',5); m.put('6',6); m.put('7',7); m.put('8',8); m.put('9',9);
        m.put('A',0); m.put('B',1); m.put('C',2); m.put('D',3); m.put('E',4);
        m.put('F',5); m.put('G',6); m.put('H',7); m.put('I',8); m.put('J',9);
        m.put('K',10); m.put('L',11); m.put('M',12); m.put('N',13); m.put('O',14);
        m.put('P',15); m.put('Q',16); m.put('R',17); m.put('S',18); m.put('T',19);
        m.put('U',20); m.put('V',21); m.put('W',22); m.put('X',23); m.put('Y',24);
        m.put('Z',25);
        return m;
    }

    /**
     * Inverte una mappa char->char (valori e chiavi).
     */
    private static Map<Character, Character> invert(Map<Character, Character> base) {
        Map<Character, Character> inv = new HashMap<>();
        for (Map.Entry<Character, Character> e : base.entrySet()) {
            inv.put(e.getValue(), e.getKey());
        }
        return inv;
    }

    /**
     * Classe che rappresenta il risultato della validazione:
     *  - booleano di validità
     *  - lista dei messaggi esplicativi
     */
    private static class ValidationResult {
        final boolean valid;
        final List<String> messages;
        ValidationResult(boolean valid, List<String> messages) {
            this.valid = valid;
            this.messages = messages;
        }
    }
}
