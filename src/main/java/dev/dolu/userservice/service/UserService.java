package dev.dolu.userservice.service;

import dev.dolu.userservice.models.User;
import dev.dolu.userservice.repository.UserRepository;
import dev.dolu.userservice.utils.JwtUtils;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final BCryptPasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final VerificationService verificationService;

    @Autowired
    public UserService(UserRepository userRepository, JwtUtils jwtUtils, VerificationService verificationService) {
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.verificationService = verificationService;
    }

    /**
     * Registers a new user by validating inputs, hashing their password, and saving the User entity.
     * If the email or username is already taken, throws a 409 Conflict error.
     *
     * @param user User object containing registration details.
     * @return The saved User object with sensitive fields like the password hashed.
     */
    public User registerUser(User user) {
        // Validate that the username and email are not already in use
        if (userRepository.existsByUsername(user.getUsername())) {
            logger.warn("Registration failed: Username '{}' is already taken.", user.getUsername());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken.");
        }

        if (userRepository.existsByEmail(user.getEmail())) {
            logger.warn("Registration failed: Email '{}' is already in use.", user.getEmail());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use.");
        }

        // Hash the user's password for secure storage
        String hashedPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(hashedPassword);
        user.setEnabled(false); // Disable user until email verification is complete

        // Save the user to the database
        User savedUser = userRepository.save(user);

        // Send email verification token
        try {
            verificationService.createAndSendVerificationToken(savedUser);
            logger.info("Verification token sent to '{}'.", user.getEmail());
        } catch (MessagingException e) {
            logger.error("Failed to send verification email to '{}'.", user.getEmail(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to send verification email. Please try again later.");
        }

        return savedUser;
    }

    /**
     * Authenticates a user by their username and password, and issues JWT tokens upon successful login.
     * If the account is not verified, resends the verification token and returns a 403 Forbidden error.
     *
     * @param username The user's username.
     * @param password The user's raw password.
     * @return A map containing the access and refresh tokens.
     * @throws MessagingException If an error occurs while resending the verification token.
     */
    public Map<String, String> login(String username, String password) throws MessagingException {
        // Retrieve user from database
        User user = userRepository.findByUsername(username);

        if (user != null && passwordEncoder.matches(password, user.getPassword())) {
            // If user exists and password matches

            // Check if user is enabled (verified)
            if (!user.isEnabled()) {
                // If not verified, resend verification token and return an error
                verificationService.resendVerificationToken(user);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account not verified. A new verification email has been sent.");
            }

            // Generate access and refresh tokens
            String accessToken = jwtUtils.generateJwtToken(username);
            String refreshToken = jwtUtils.generateRefreshToken(username);

            // Store refresh token securely (Redis-backed storage)
            jwtUtils.storeRefreshToken(refreshToken, username);

            // Return the tokens
            Map<String, String> tokens = new HashMap<>();
            tokens.put("accessToken", accessToken);
            tokens.put("refreshToken", refreshToken);
            return tokens;
        }

        // Invalid credentials
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
    }


    /**
     * Finds a user by their ID.
     *
     * @param userId The user's ID.
     * @return The User object if found, or null if not found.
     */
    public User findUserById(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }
}
