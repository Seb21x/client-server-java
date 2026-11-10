import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Server {

    // ── Wspólny stan serwera (bezpieczny wątkowo) ──────────────────────────

    // AtomicInteger gwarantuje, że każde zadanie dostanie unikalne ID,
    // nawet gdy wielu klientów zleca zadania jednocześnie.
    static final AtomicInteger nextId = new AtomicInteger(1);

    // ConcurrentHashMap pozwala wielu wątkom jednocześnie czytać i zapisywać
    // zadania bez ryzyka uszkodzenia danych (w przeciwieństwie do zwykłej HashMap).
    static final ConcurrentHashMap<Integer, Task> tasks = new ConcurrentHashMap<>();

    // Pula wątków do wykonywania zadań w tle – tworzy nowe wątki w miarę potrzeb.
    static final ExecutorService taskExecutor = Executors.newCachedThreadPool();

    // ── Klasa zadania ──────────────────────────────────────────────────────

    static class Task {
        final int id;
        final String type;       // REVERSE, PRIMES lub DELAY
        final String data;       // dane wejściowe zadania
        final String clientId;   // identyfikator klienta, który zlecił zadanie
        volatile String status;  // volatile = zmiana widoczna natychmiast we wszystkich wątkach
        volatile String result;

        Task(int id, String type, String data, String clientId) {
            this.id = id;
            this.type = type;
            this.data = data;
            this.clientId = clientId;
            this.status = "PENDING";  // zadanie czeka w kolejce
            this.result = null;
        }
    }

    // ── Main – uruchomienie serwera ────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        int port = 12345;

        // ServerSocket nasłuchuje na podanym porcie i czeka na połączenia klientów.
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Serwer nasłuchuje na porcie " + port);

        // Osobna pula wątków do obsługi połączeń klientów
        // (każdy klient = osobny wątek).
        ExecutorService clientPool = Executors.newCachedThreadPool();

        // Nieskończona pętla – serwer cały czas przyjmuje nowych klientów.
        while (true) {
            // accept() blokuje się, aż nowy klient się połączy.
            // Zwraca Socket reprezentujący połączenie z tym konkretnym klientem.
            Socket clientSocket = serverSocket.accept();
            String clientId = clientSocket.getRemoteSocketAddress().toString();
            System.out.println("Nowy klient: " + clientId);

            // Obsługę klienta przekazujemy do osobnego wątku,
            // żeby serwer mógł od razu przyjąć kolejne połączenie.
            clientPool.submit(() -> handleClient(clientSocket, clientId));
        }
    }

    // ── Obsługa pojedynczego klienta ───────────────────────────────────────

    static void handleClient(Socket socket, String clientId) {
        // try-with-resources: automatycznie zamknie strumienie i gniazdo po zakończeniu.
        try (
            // BufferedReader czyta tekst linia po linii z gniazda.
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // PrintWriter wysyła tekst do klienta. autoFlush=true oznacza,
            // że dane są wysyłane natychmiast po println(), bez ręcznego flush().
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            out.println("Witaj! Komendy: SUBMIT <TYP> <DANE>, STATUS <ID>, RESULT <ID>, LIST, QUIT");

            String line;
            // Pętla czyta komendy klienta, aż klient się rozłączy (readLine() zwróci null).
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                System.out.println("[" + clientId + "] > " + trimmed);

                // Rozdzielamy komendę na części po spacjach.
                String[] parts = trimmed.split("\\s+", 3);
                String command = parts[0].toUpperCase();

                switch (command) {
                    case "SUBMIT":
                        handleSubmit(parts, clientId, out);
                        break;
                    case "STATUS":
                        handleStatus(parts, out);
                        break;
                    case "RESULT":
                        handleResult(parts, out);
                        break;
                    case "LIST":
                        handleList(clientId, out);
                        break;
                    case "QUIT":
                        out.println("Do widzenia!");
                        return; // kończymy obsługę tego klienta
                    default:
                        out.println("ERROR: Nieznana komenda: " + command);
                }
            }
        } catch (IOException e) {
            System.out.println("Klient rozłączony: " + clientId);
        }
    }

    // ── Obsługa komendy SUBMIT ─────────────────────────────────────────────

    static void handleSubmit(String[] parts, String clientId, PrintWriter out) {
        // Walidacja: potrzebujemy co najmniej "SUBMIT TYP DANE"
        if (parts.length < 3) {
            out.println("ERROR: Użycie: SUBMIT <TYP> <DANE>");
            return;
        }

        String type = parts[1].toUpperCase();
        String data = parts[2];

        // Sprawdzamy, czy typ zadania jest obsługiwany
        if (!type.equals("REVERSE") && !type.equals("PRIMES") && !type.equals("DELAY")) {
            out.println("ERROR: Nieznany typ zadania: " + type + " (dostępne: REVERSE, PRIMES, DELAY)");
            return;
        }

        // getAndIncrement() atomowo zwraca bieżącą wartość i zwiększa o 1.
        // Dzięki temu dwa wątki nigdy nie dostaną tego samego ID.
        int id = nextId.getAndIncrement();
        Task task = new Task(id, type, data, clientId);
        tasks.put(id, task);

        // Od razu zwracamy ID klientowi – nie czekamy na zakończenie zadania.
        out.println("OK: Zadanie zarejestrowane z ID=" + id);

        // Zlecamy wykonanie zadania w tle (w osobnym wątku z puli).
        taskExecutor.submit(() -> executeTask(task));
    }

    // ── Wykonanie zadania w tle ────────────────────────────────────────────

    static void executeTask(Task task) {
        task.status = "RUNNING";  // zmiana widoczna dzięki volatile
        try {
            String result;
            switch (task.type) {
                case "REVERSE":
                    // Odwracanie tekstu
                    result = new StringBuilder(task.data).reverse().toString();
                    break;

                case "PRIMES":
                    // Obliczanie liczb pierwszych od 2 do n
                    int n = Integer.parseInt(task.data);
                    List<Integer> primes = new ArrayList<>();
                    for (int i = 2; i <= n; i++) {
                        if (isPrime(i)) primes.add(i);
                    }
                    result = primes.toString();
                    break;

                case "DELAY":
                    // Czekanie podaną liczbę sekund
                    int seconds = Integer.parseInt(task.data);
                    Thread.sleep(seconds * 1000L);
                    result = "Ukończono opóźnienie " + seconds + "s";
                    break;

                default:
                    result = "Nieobsługiwany typ";
            }
            task.result = result;
            task.status = "FINISHED";
        } catch (Exception e) {
            task.result = "Błąd: " + e.getMessage();
            task.status = "ERROR";
        }
        System.out.println("Zadanie #" + task.id + " zakończone: " + task.status);
    }

    // Prosta metoda sprawdzająca, czy liczba jest pierwsza.
    static boolean isPrime(int n) {
        if (n < 2) return false;
        for (int i = 2; i * i <= n; i++) {
            if (n % i == 0) return false;
        }
        return true;
    }

    // ── Obsługa komendy STATUS ─────────────────────────────────────────────

    static void handleStatus(String[] parts, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERROR: Użycie: STATUS <ID>");
            return;
        }
        try {
            int id = Integer.parseInt(parts[1]);
            Task task = tasks.get(id);
            if (task == null) {
                out.println("ERROR: Nie znaleziono zadania o ID=" + id);
            } else {
                out.println("STATUS: Zadanie #" + id + " -> " + task.status);
            }
        } catch (NumberFormatException e) {
            out.println("ERROR: ID musi być liczbą");
        }
    }

    // ── Obsługa komendy RESULT ─────────────────────────────────────────────

    static void handleResult(String[] parts, PrintWriter out) {
        if (parts.length < 2) {
            out.println("ERROR: Użycie: RESULT <ID>");
            return;
        }
        try {
            int id = Integer.parseInt(parts[1]);
            Task task = tasks.get(id);
            if (task == null) {
                out.println("ERROR: Nie znaleziono zadania o ID=" + id);
            } else if (!task.status.equals("FINISHED") && !task.status.equals("ERROR")) {
                out.println("WAIT: Zadanie #" + id + " jeszcze nie zakończone (status: " + task.status + ")");
            } else {
                out.println("RESULT: " + task.result);
            }
        } catch (NumberFormatException e) {
            out.println("ERROR: ID musi być liczbą");
        }
    }

    // ── Obsługa komendy LIST ───────────────────────────────────────────────

    static void handleList(String clientId, PrintWriter out) {
        // Filtrujemy zadania należące do tego konkretnego klienta.
        List<String> clientTasks = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (task.clientId.equals(clientId)) {
                clientTasks.add("#" + task.id + " [" + task.type + "] " + task.status);
            }
        }
        if (clientTasks.isEmpty()) {
            out.println("LIST: Brak zadań");
        } else {
            out.println("LIST: " + String.join(", ", clientTasks));
        }
    }
}

