import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        String host = "localhost";
        int port = 12345;

        // Łączymy się z serwerem przez gniazdo TCP.
        // try-with-resources zamknie gniazdo i strumienie automatycznie.
        try (
            Socket socket = new Socket(host, port);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Scanner scanner = new Scanner(System.in)
        ) {
            // Odczytujemy i wyświetlamy wiadomość powitalną od serwera.
            String welcome = in.readLine();
            System.out.println("[Serwer] " + welcome);

            System.out.println("Wpisz komendę (lub QUIT aby zakończyć):");

            // Główna pętla klienta: czytaj komendę -> wyślij -> odbierz odpowiedź.
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break; // Ctrl+D / koniec wejścia

                String command = scanner.nextLine().trim();
                if (command.isEmpty()) continue;

                // Wysyłamy komendę do serwera (println dodaje \n, autoFlush wysyła od razu).
                out.println(command);

                // Odbieramy odpowiedź od serwera.
                String response = in.readLine();
                if (response == null) {
                    System.out.println("Serwer zamknął połączenie.");
                    break;
                }
                System.out.println("[Serwer] " + response);

                // Jeśli klient wpisał QUIT, kończymy pętlę.
                if (command.equalsIgnoreCase("QUIT")) break;
            }

        } catch (ConnectException e) {
            System.out.println("Nie można połączyć się z serwerem na " + host + ":" + port);
            System.out.println("Upewnij się, że serwer jest uruchomiony.");
        } catch (IOException e) {
            System.out.println("Błąd połączenia: " + e.getMessage());
        }
    }
}

