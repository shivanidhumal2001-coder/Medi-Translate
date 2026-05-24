package com.meditranslate.entity;

import com.meditranslate.util.JsonMapConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "report_analysis")
public class ReportAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportSourceType sourceType;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String originalText;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String extractedText;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String simpleSummary;

    @Column(nullable = false, length = 12)
    private String summaryLanguage = "en";

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String keyHighlights;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String disclaimer;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, String> multilingualSummaries = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UrgencyLevel urgencyLevel;

    @Column(nullable = false)
    private Double trustScore = 0.0;

    @Column(length = 260)
    private String originalFileName;

    @Column(length = 280)
    private String storedFilePath;

    @Column(length = 120)
    private String guestSessionId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReportFinding> findings = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatMessage> chatMessages = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MedicationReminder> reminders = new ArrayList<>();

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void addFinding(ReportFinding finding) {
        finding.setReport(this);
        findings.add(finding);
    }

    public void addChatMessage(ChatMessage chatMessage) {
        chatMessage.setReport(this);
        chatMessages.add(chatMessage);
    }

    public void addReminder(MedicationReminder reminder) {
        reminder.setReport(this);
        reminders.add(reminder);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ReportSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(ReportSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public String getSimpleSummary() {
        return simpleSummary;
    }

    public void setSimpleSummary(String simpleSummary) {
        this.simpleSummary = simpleSummary;
    }

    public String getSummaryLanguage() {
        return summaryLanguage;
    }

    public void setSummaryLanguage(String summaryLanguage) {
        this.summaryLanguage = summaryLanguage;
    }

    public String getKeyHighlights() {
        return keyHighlights;
    }

    public void setKeyHighlights(String keyHighlights) {
        this.keyHighlights = keyHighlights;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    public Map<String, String> getMultilingualSummaries() {
        return multilingualSummaries;
    }

    public void setMultilingualSummaries(Map<String, String> multilingualSummaries) {
        this.multilingualSummaries = multilingualSummaries;
    }

    public UrgencyLevel getUrgencyLevel() {
        return urgencyLevel;
    }

    public void setUrgencyLevel(UrgencyLevel urgencyLevel) {
        this.urgencyLevel = urgencyLevel;
    }

    public Double getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(Double trustScore) {
        this.trustScore = trustScore;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoredFilePath() {
        return storedFilePath;
    }

    public void setStoredFilePath(String storedFilePath) {
        this.storedFilePath = storedFilePath;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public void setGuestSessionId(String guestSessionId) {
        this.guestSessionId = guestSessionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public List<ReportFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<ReportFinding> findings) {
        this.findings = findings;
    }

    public List<ChatMessage> getChatMessages() {
        return chatMessages;
    }

    public List<MedicationReminder> getReminders() {
        return reminders;
    }

    public boolean isOwnedBy(UserAccount userAccount) {
        return user != null && userAccount != null && user.getId().equals(userAccount.getId());
    }
}
