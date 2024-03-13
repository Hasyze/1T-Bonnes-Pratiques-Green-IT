package polytech.projets10.g1._1tbonnespratiquesgreenit.controllers;

import jakarta.ws.rs.NotFoundException;
import org.apache.coyote.BadRequestException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import polytech.projets10.g1._1tbonnespratiquesgreenit.entities.Game;
import polytech.projets10.g1._1tbonnespratiquesgreenit.entities.GameStatus;
import polytech.projets10.g1._1tbonnespratiquesgreenit.repositories.GameRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository gameRepository;

    @Autowired
    Keycloak keycloak;

    @Value("${keycloak.config.realm}")
    private String realm;

    public GameController(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @GetMapping("")
    @PreAuthorize("hasAuthority('ROLE_user')")
    public List<Game> getAllGames() {
        return gameRepository.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_user')")
    public ResponseEntity<Game> getGame(@PathVariable Long id) {
        var game = gameRepository.findById(id);
        if (game.isPresent()) return ResponseEntity.ok(game.get());
        return ResponseEntity.ok(null);

    }

    @PostMapping("/")
    @PreAuthorize("hasAuthority('ROLE_user')")
    public ResponseEntity<Game> createGame(@RequestBody Game game) throws BadRequestException, URISyntaxException {
        if (game.getId() != null) throw new BadRequestException("A new game cannot already have an ID");
        var result = gameRepository.save(game);
        return ResponseEntity.created(new URI("/api/games/" + result.getId())).body(result);
    }

    @PostMapping("/{gameId}/join")
    @PreAuthorize("hasAuthority('ROLE_user')")
    public ResponseEntity<Game> userJoinsGame(@PathVariable Long gameId, @RequestBody String userId) {
        var game = gameRepository.findById(gameId);
        if (!game.isPresent())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette partie n'existe pas: " + gameId);

        Game gameObj = game.get();
        if (gameObj.getStatus() != GameStatus.WAITING_TO_START)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cette partie est en cours: " + gameId);

        if (this.getPlayersCountInGame(gameId) >= 4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cette partie est pleine: " + gameId);

        UserResource user = keycloak.realm(this.realm).users().get(userId);
        try {
            UserRepresentation userRepresentation = user.toRepresentation();
            Map<String, List<String>> existingAttributes = userRepresentation.getAttributes();

            List<String> userGames = existingAttributes.get("game");

            if (userGames == null) userGames = new ArrayList<>();
            if (userGames.contains(String.valueOf(gameId)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cet utilisateur est déjà dans cette partie: " + gameId);

            userGames.add(String.valueOf(gameId));

            existingAttributes.put("game", userGames);
            user.update(userRepresentation);
            return ResponseEntity.ok().build();
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cet utilisateur n'existe pas: " + userId, e);
        }
    }

    @PostMapping("/{gameId}/leave")
    @PreAuthorize("hasAuthority('ROLE_user')")
    public ResponseEntity<Game> userLeavesGame(@PathVariable Long gameId, @RequestBody String userId) {
        var game = gameRepository.findById(gameId);
        if (!game.isPresent())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette partie n'existe pas: " + gameId);

        UserResource user = keycloak.realm(this.realm).users().get(userId);
        try {
            UserRepresentation userRepresentation = user.toRepresentation();
            Map<String, List<String>> existingAttributes = userRepresentation.getAttributes();

            List<String> userGames = existingAttributes.get("game");

            if (userGames == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cet utilisateur (" + userId + ") ne fait partie d'aucune partie");
            if (!userGames.contains(String.valueOf(gameId)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cet utilisateur n'appartient pas à cette partie: " + gameId);

            userGames.remove(String.valueOf(gameId));

            existingAttributes.put("game", userGames);

            user.update(userRepresentation);
            return ResponseEntity.ok().build();
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cet utilisateur n'existe pas: " + userId, e);
        }
    }

    private int getPlayersCountInGame(Long gameId) {
        List<UserRepresentation> users = keycloak.realm(realm).users().searchByAttributes("game:" + gameId);
        return users.size();
    }
}
