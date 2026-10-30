package com.aidocassistant.repository;

import com.aidocassistant.model.Conversation;
import com.aidocassistant.model.ConversationDocument;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.Message;
import com.aidocassistant.model.MessageRole;
import com.aidocassistant.model.MessageSource;
import com.aidocassistant.model.User;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive integration tests for JPA entities, constraints, Flyway schema, and repositories.
 * Uses an isolated H2 database in MySQL compatibility mode.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
class RepositoryAndSchemaTests {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationDocumentRepository conversationDocumentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageSourceRepository messageSourceRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = new User(UUID.randomUUID(), "alice@example.com", "$2a$12$hashAlice123", "Alice Smith", "1234567890");
        bob = new User(UUID.randomUUID(), "bob@example.com", "$2a$12$hashBob123", "Bob Jones", "0987654321");
        userRepository.save(alice);
        userRepository.save(bob);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Verify Flyway migration V1 script compiles and executes successfully on datasource")
    void testFlywayMigrationExecution() {
        // Configure a standalone Flyway instance against a separate in-memory database to test raw SQL DDL
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway_testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();

        int appliedMigrations = flyway.migrate().migrationsExecuted;
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Verify User creation and email uniqueness constraint")
    void testUserEmailUniquenessConstraint() {
        User duplicateAlice = new User(UUID.randomUUID(), "alice@example.com", "$2a$12$otherHash", "Alice Clone");

        assertThatThrownBy(() -> {
            userRepository.saveAndFlush(duplicateAlice);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify Document persistence, status enum, and user ownership queries")
    void testDocumentOwnershipAndStatusQueries() {
        Document doc1 = new Document(UUID.randomUUID(), alice, "report_2025.pdf", "doc-uuid-1.pdf", "sha256hash1", 1024L);
        doc1.setStatus(DocumentStatus.READY);
        doc1.setTotalPages(10);
        doc1.setTotalChunks(25);
        documentRepository.save(doc1);

        Document doc2 = new Document(UUID.randomUUID(), alice, "draft.pdf", "doc-uuid-2.pdf", "sha256hash2", 2048L);
        doc2.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc2);

        Document bobsDoc = new Document(UUID.randomUUID(), bob, "bobs_secrets.pdf", "doc-uuid-3.pdf", "sha256hash3", 512L);
        bobsDoc.setStatus(DocumentStatus.READY);
        documentRepository.save(bobsDoc);

        entityManager.flush();
        entityManager.clear();

        // Alice should only retrieve her own documents
        List<Document> aliceDocs = documentRepository.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(aliceDocs).hasSize(2);
        assertThat(aliceDocs).extracting(Document::getOriginalFileName).containsExactlyInAnyOrder("report_2025.pdf", "draft.pdf");

        // Alice querying Bob's document ID returns empty (cross-user isolation)
        Optional<Document> crossUserDoc = documentRepository.findByIdAndUserId(bobsDoc.getId(), alice.getId());
        assertThat(crossUserDoc).isEmpty();

        // Alice querying multiple doc IDs with READY status filter
        List<Document> readyDocs = documentRepository.findAllByIdInAndUserIdAndStatus(
                List.of(doc1.getId(), doc2.getId(), bobsDoc.getId()),
                alice.getId(),
                DocumentStatus.READY
        );
        assertThat(readyDocs).hasSize(1);
        assertThat(readyDocs.get(0).getId()).isEqualTo(doc1.getId());

        // Document count by user and status
        assertThat(documentRepository.countByUserId(alice.getId())).isEqualTo(2);
        assertThat(documentRepository.countByUserIdAndStatus(alice.getId(), DocumentStatus.READY)).isEqualTo(1);
        assertThat(documentRepository.countByUserIdAndStatus(alice.getId(), DocumentStatus.PROCESSING)).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify Conversation persistence and ownership isolation")
    void testConversationOwnershipIsolation() {
        Conversation convAlice = new Conversation(UUID.randomUUID(), alice, "Alice's Chat");
        Conversation convBob = new Conversation(UUID.randomUUID(), bob, "Bob's Chat");
        conversationRepository.save(convAlice);
        conversationRepository.save(convBob);
        entityManager.flush();
        entityManager.clear();

        List<Conversation> aliceConvs = conversationRepository.findByUserIdOrderByUpdatedAtDesc(alice.getId());
        assertThat(aliceConvs).hasSize(1);
        assertThat(aliceConvs.get(0).getTitle()).isEqualTo("Alice's Chat");

        // Alice cannot retrieve Bob's conversation
        Optional<Conversation> crossConversation = conversationRepository.findByIdAndUserId(convBob.getId(), alice.getId());
        assertThat(crossConversation).isEmpty();
    }

    @Test
    @DisplayName("Verify ConversationDocument uniqueness constraint (no duplicate attachment)")
    void testConversationDocumentUniqueness() {
        Document doc = new Document(UUID.randomUUID(), alice, "specs.pdf", "specs.pdf", "hash", 100L);
        documentRepository.save(doc);

        Conversation conv = new Conversation(UUID.randomUUID(), alice, "Specs Discussion");
        conversationRepository.save(conv);

        ConversationDocument link1 = new ConversationDocument(UUID.randomUUID(), conv, doc);
        conversationDocumentRepository.saveAndFlush(link1);

        ConversationDocument duplicateLink = new ConversationDocument(UUID.randomUUID(), conv, doc);
        assertThatThrownBy(() -> {
            conversationDocumentRepository.saveAndFlush(duplicateLink);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Verify Message and Citation (MessageSource) persistence with ordered retrieval")
    void testMessageAndMessageSourcePersistence() {
        Document doc = new Document(UUID.randomUUID(), alice, "handbook.pdf", "hb.pdf", "hash123", 4096L);
        documentRepository.save(doc);

        Conversation conv = new Conversation(UUID.randomUUID(), alice, "Company Policy");
        conversationRepository.save(conv);

        Message userMsg = new Message(UUID.randomUUID(), conv, MessageRole.USER, "What is the vacation policy?");
        Message assistantMsg = new Message(UUID.randomUUID(), conv, MessageRole.ASSISTANT, "Employees receive 20 days off.");
        messageRepository.save(userMsg);
        messageRepository.save(assistantMsg);

        MessageSource citation = new MessageSource(
                UUID.randomUUID(),
                assistantMsg,
                doc,
                14,
                2,
                0.89f,
                "Employees are entitled to 20 days of paid vacation per year."
        );
        messageSourceRepository.save(citation);

        entityManager.flush();
        entityManager.clear();

        // Verify chronological messages
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(history.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);

        // Verify citation retrieval
        List<MessageSource> citations = messageSourceRepository.findByMessageIdOrderByRelevanceScoreDesc(assistantMsg.getId());
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).getPageNumber()).isEqualTo(14);
        assertThat(citations.get(0).getRelevanceScore()).isEqualTo(0.89f);
        assertThat(citations.get(0).getExcerptText()).contains("20 days of paid vacation");
    }

    @Test
    @DisplayName("Verify Foreign Key constraint rejects orphan record insertion")
    void testForeignKeyConstraintViolation() {
        User unpersistedUser = new User(UUID.randomUUID(), "phantom@example.com", "hash", "Phantom");
        // Attempt to save Document referencing unpersisted user without saving user first
        Document orphanDoc = new Document(UUID.randomUUID(), unpersistedUser, "orphan.pdf", "orphan.pdf", "hash", 100L);

        assertThatThrownBy(() -> {
            documentRepository.saveAndFlush(orphanDoc);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
