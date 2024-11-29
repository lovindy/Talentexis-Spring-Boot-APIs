package com.example.springrestful.security;

import com.example.springrestful.entity.EmployeeInvitation;
import com.example.springrestful.exception.EmailSendingException;
import com.example.springrestful.exception.InvalidInvitationException;
import com.example.springrestful.util.EmailUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private static final String INVITATION_CACHE_PREFIX = "invitation:";
    private static final String INVITATION_QUEUE_KEY = "invitation:queue";
    private static final Duration INVITATION_CACHE_DURATION = Duration.ofDays(7);

    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final EmailQueueService emailQueueService;
    private final TemplateEngine templateEngine;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${application.invitation.base-url}")
    private String invitationBaseUrl;

    public String generateVerificationCode() {
        return EmailUtil.generateVerificationCode();
    }

    public String hashVerificationCode(String plainCode) {
        return EmailUtil.hashVerificationCode(plainCode, passwordEncoder);
    }

    public void sendVerificationCode(String toEmail, String verificationCode) {
        try {
            if (toEmail == null || toEmail.trim().isEmpty()) {
                throw new IllegalArgumentException("Recipient email cannot be null or empty");
            }

            emailQueueService.queueEmail(toEmail, verificationCode);
            EmailUtil.logEmailSuccess("Verification email queued", toEmail);

        } catch (Exception e) {
            EmailUtil.logEmailError("Failed to queue verification email", toEmail, e);
            throw new RuntimeException("Failed to queue verification email: " + e.getMessage(), e);
        }
    }

    @Async
    protected void processAndSendEmail(String toEmail, String verificationCode) {
        try {
            SimpleMailMessage message = EmailUtil.createVerificationEmail(fromEmail, toEmail, verificationCode);
            mailSender.send(message);
            EmailUtil.logEmailSuccess("Email sent successfully", toEmail);

        } catch (Exception e) {
            EmailUtil.logEmailError("Failed to send email", toEmail, e);
            handleEmailFailure(toEmail, verificationCode);
        }
    }

    public void sendInvitationEmail(EmployeeInvitation invitation) {
        try {
            cacheInvitationData(invitation);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());

            String emailContent = generateInvitationEmailContent(invitation);

            helper.setTo(invitation.getEmail());
            helper.setSubject("Invitation to join " + invitation.getOrganization().getName());
            helper.setText(emailContent, true);
            helper.setFrom(fromEmail);

            queueInvitationEmail(invitation.getEmail(), emailContent);
            EmailUtil.logEmailSuccess("Invitation email queued", invitation.getEmail());

        } catch (MessagingException e) {
            EmailUtil.logEmailError("Failed to prepare invitation email", invitation.getEmail(), e);
            throw new EmailSendingException("Failed to send invitation email", e);
        }
    }

    private String generateInvitationEmailContent(EmployeeInvitation invitation) {
        Context context = new Context();
        Map<String, Object> variables = new HashMap<>();
        variables.put("organizationName", invitation.getOrganization().getName());
        variables.put("invitationLink", generateInvitationLink(invitation.getInvitationToken()));
        variables.put("expiryDate", invitation.getTokenExpiry().toLocalDate().toString());
        context.setVariables(variables);

        return templateEngine.process("invitation-email", context);
    }

    private void cacheInvitationData(EmployeeInvitation invitation) {
        try {
            String cacheKey = INVITATION_CACHE_PREFIX + invitation.getInvitationToken();
            Map<String, String> invitationData = new HashMap<>();
            invitationData.put("email", invitation.getEmail());
            invitationData.put("organizationId", invitation.getOrganization().getId().toString());
            invitationData.put("status", invitation.getStatus().toString());
            invitationData.put("expiryDate", invitation.getTokenExpiry().toString());

            redisTemplate.opsForHash().putAll(cacheKey, invitationData);
            redisTemplate.expire(cacheKey, INVITATION_CACHE_DURATION);

            log.debug("🗄️ Invitation data cached with key: {}", cacheKey);
        } catch (Exception e) {
            EmailUtil.logEmailError("Failed to cache invitation data", invitation.getEmail(), e);
            throw new InvalidInvitationException("Failed to process invitation" + e);
        }
    }

    public Optional<Map<Object, Object>> getInvitationData(String token) {
        String cacheKey = INVITATION_CACHE_PREFIX + token;
        Map<Object, Object> invitationData = redisTemplate.opsForHash().entries(cacheKey);
        return invitationData.isEmpty() ? Optional.empty() : Optional.of(invitationData);
    }

    private void queueInvitationEmail(String email, String content) {
        try {
            Map<String, String> emailData = EmailUtil.createInvitationQueueData(email, content);
            redisTemplate.opsForList().rightPush(INVITATION_QUEUE_KEY,
                    objectMapper.writeValueAsString(emailData));
        } catch (Exception e) {
            EmailUtil.logEmailError("Failed to queue invitation email", email, e);
            throw new EmailSendingException("Failed to queue invitation email", e);
        }
    }

    private String generateInvitationLink(String token) {
        return invitationBaseUrl + "/invitations/" + token + "/accept";
    }

    private void handleEmailFailure(String toEmail, String verificationCode) {
        log.warn("⚠️ Implementing retry logic for failed email to: {}", toEmail);
        // Add to dead letter queue or retry queue
    }
}