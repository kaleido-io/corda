package net.corda.node.services.persistence

import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.internal.ThreadBox
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.bufferUntilSubscribed
import net.corda.core.internal.concurrent.doneFuture
import net.corda.core.messaging.DataFeed
import net.corda.core.node.services.TransactionStorage
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryException
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.DEFAULT_PAGE_SIZE
import net.corda.core.node.services.vault.MAX_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.serialization.*
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.core.toFuture
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.node.CordaClock
import net.corda.node.services.api.WritableTransactionStorage
import net.corda.node.services.statemachine.FlowStateMachineImpl
import net.corda.node.utilities.AppendOnlyPersistentMapBase
import net.corda.node.utilities.WeightBasedAppendOnlyPersistentMap
import net.corda.nodeapi.internal.persistence.*
import net.corda.serialization.internal.CordaSerializationEncoding.SNAPPY
import rx.Observable
import rx.subjects.PublishSubject
import java.time.Instant
import java.util.*
import javax.persistence.*
import kotlin.streams.toList

class DBTransactionStorage(private val database: CordaPersistence, cacheFactory: NamedCacheFactory,
                           private val clock: CordaClock) : WritableTransactionStorage, SingletonSerializeAsToken() {

    @Suppress("MagicNumber") // database column width
    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}transactions")
    class DBTransaction(
            @Id
            @Column(name = "tx_id", length = 64, nullable = false)
            val txId: String,

            @Column(name = "state_machine_run_id", length = 36, nullable = true)
            val stateMachineRunId: String?,

            @Lob
            @Column(name = "transaction_value", nullable = false)
            val transaction: ByteArray,

            @Column(name = "status", nullable = false, length = 1)
            @Convert(converter = TransactionStatusConverter::class)
            val status: TransactionStatus,

            @Column(name = "timestamp", nullable = false)
            val timestamp: Instant
            )

    enum class TransactionStatus {
        UNVERIFIED,
        VERIFIED;

        fun toDatabaseValue(): String {
            return when (this) {
                UNVERIFIED -> "U"
                VERIFIED -> "V"
            }
        }

        fun isVerified(): Boolean {
            return this == VERIFIED
        }

        companion object {
            fun fromDatabaseValue(databaseValue: String): TransactionStatus {
                return when (databaseValue) {
                    "V" -> VERIFIED
                    "U" -> UNVERIFIED
                    else -> throw UnexpectedStatusValueException(databaseValue)
                }
            }
        }

        private class UnexpectedStatusValueException(status: String) : Exception("Found unexpected status value $status in transaction store")
    }

    @Converter
    class TransactionStatusConverter : AttributeConverter<TransactionStatus, String> {
        override fun convertToDatabaseColumn(attribute: TransactionStatus): String {
            return attribute.toDatabaseValue()
        }

        override fun convertToEntityAttribute(dbData: String): TransactionStatus {
            return TransactionStatus.fromDatabaseValue(dbData)
        }
    }

    private companion object {
        // Rough estimate for the average of a public key and the transaction metadata - hard to get exact figures here,
        // as public keys can vary in size a lot, and if someone else is holding a reference to the key, it won't add
        // to the memory pressure at all here.
        private const val transactionSignatureOverheadEstimate = 1024

        private val logger = contextLogger()

        private fun contextToUse(): SerializationContext {
            return if (effectiveSerializationEnv.serializationFactory.currentContext?.useCase == SerializationContext.UseCase.Storage) {
                effectiveSerializationEnv.serializationFactory.currentContext!!
            } else {
                SerializationDefaults.STORAGE_CONTEXT
            }
        }

        fun createTransactionsMap(cacheFactory: NamedCacheFactory, clock: CordaClock)
                : AppendOnlyPersistentMapBase<SecureHash, TxCacheValue, DBTransaction, String> {
            return WeightBasedAppendOnlyPersistentMap<SecureHash, TxCacheValue, DBTransaction, String>(
                    cacheFactory = cacheFactory,
                    name = "DBTransactionStorage_transactions",
                    toPersistentEntityKey = SecureHash::toString,
                    fromPersistentEntity = {
                        SecureHash.parse(it.txId) to TxCacheValue(
                                it.transaction.deserialize(context = contextToUse()),
                                it.status)
                    },
                    toPersistentEntity = { key: SecureHash, value: TxCacheValue ->
                        DBTransaction(
                                txId = key.toString(),
                                stateMachineRunId = FlowStateMachineImpl.currentStateMachine()?.id?.uuid?.toString(),
                                transaction = value.toSignedTx().serialize(context = contextToUse().withEncoding(SNAPPY)).bytes,
                                status = value.status,
                                timestamp = clock.instant()
                        )
                    },
                    persistentEntityClass = DBTransaction::class.java,
                    weighingFunc = { hash, tx -> hash.size + weighTx(tx) }
            )
        }

        private fun weighTx(tx: AppendOnlyPersistentMapBase.Transactional<TxCacheValue>): Int {
            val actTx = tx.peekableValue ?: return 0
            return actTx.sigs.sumBy { it.size + transactionSignatureOverheadEstimate } + actTx.txBits.size
        }

        private val log = contextLogger()
    }

    private val txStorage = ThreadBox(createTransactionsMap(cacheFactory, clock))

    private fun updateTransaction(txId: SecureHash): Boolean {
        val session = currentDBSession()
        val criteriaBuilder = session.criteriaBuilder
        val criteriaUpdate = criteriaBuilder.createCriteriaUpdate(DBTransaction::class.java)
        val updateRoot = criteriaUpdate.from(DBTransaction::class.java)
        criteriaUpdate.set(updateRoot.get<TransactionStatus>(DBTransaction::status.name), TransactionStatus.VERIFIED)
        criteriaUpdate.where(criteriaBuilder.and(
                criteriaBuilder.equal(updateRoot.get<String>(DBTransaction::txId.name), txId.toString()),
                criteriaBuilder.equal(updateRoot.get<TransactionStatus>(DBTransaction::status.name), TransactionStatus.UNVERIFIED)
        ))
        criteriaUpdate.set(updateRoot.get<Instant>(DBTransaction::timestamp.name), clock.instant())
        val update = session.createQuery(criteriaUpdate)
        val rowsUpdated = update.executeUpdate()
        return rowsUpdated != 0
    }

    override fun addTransaction(transaction: SignedTransaction): Boolean {
        return database.transaction {
            txStorage.locked {
                val cachedValue = TxCacheValue(transaction, TransactionStatus.VERIFIED)
                val addedOrUpdated = addOrUpdate(transaction.id, cachedValue) { k, _ -> updateTransaction(k) }
                if (addedOrUpdated) {
                    logger.debug { "Transaction ${transaction.id} has been recorded as verified" }
                    onNewTx(transaction)
                } else {
                    logger.debug { "Transaction ${transaction.id} is already recorded as verified, so no need to re-record" }
                    false
                }
            }
        }
    }

    private fun onNewTx(transaction: SignedTransaction): Boolean {
        updatesPublisher.bufferUntilDatabaseCommit().onNext(transaction)
        return true
    }

    override fun getTransaction(id: SecureHash): SignedTransaction? {
        return database.transaction {
            txStorage.content[id]?.let { if (it.status.isVerified()) it.toSignedTx() else null }
        }
    }

    override fun addUnverifiedTransaction(transaction: SignedTransaction) {
        database.transaction {
            txStorage.locked {
                val cacheValue = TxCacheValue(transaction, status = TransactionStatus.UNVERIFIED)
                val added = addWithDuplicatesAllowed(transaction.id, cacheValue)
                if (added) {
                    logger.debug { "Transaction ${transaction.id} recorded as unverified." }
                } else {
                    logger.info("Transaction ${transaction.id} already exists so no need to record.")
                }
            }
        }
    }

    override fun getTransactionInternal(id: SecureHash): Pair<SignedTransaction, Boolean>? {
        return database.transaction {
            txStorage.content[id]?.let { it.toSignedTx() to it.status.isVerified() }
        }
    }

    private val updatesPublisher = PublishSubject.create<SignedTransaction>().toSerialized()
    override val updates: Observable<SignedTransaction> = updatesPublisher.wrapWithDatabaseTransaction()

    override fun track(): DataFeed<List<SignedTransaction>, SignedTransaction> {
        return database.transaction {
            txStorage.locked {
                DataFeed(snapshot(), updates.bufferUntilSubscribed())
            }
        }
    }

    /**
    *  track with paging specification
     * */
    override fun trackWithPagingSpec(paging: PageSpecification): DataFeed<TransactionStorage.Page, SignedTransaction> {
        return database.transaction {
            var totalTransactions = 0L
            txStorage.locked {
                totalTransactions = txStorage.content.size
            }
            DataFeed(pagedSnapshot(paging, totalTransactions), updates.bufferUntilSubscribed())
        }
    }

    override fun trackTransaction(id: SecureHash): CordaFuture<SignedTransaction> {

        if (contextTransactionOrNull != null) {
            log.warn("trackTransaction is called with an already existing, open DB transaction. As a result, there might be transactions missing from the returned data feed, because of race conditions.")
        }

        return trackTransactionWithNoWarning(id)
    }

    override fun trackTransactionWithNoWarning(id: SecureHash): CordaFuture<SignedTransaction> {
        val updateFuture = updates.filter { it.id == id }.toFuture()
        return database.transaction {
            txStorage.locked {
                val existingTransaction = getTransaction(id)
                if (existingTransaction == null) {
                    updates.filter { it.id == id }.toFuture()
                    updateFuture
                } else {
                    updateFuture.cancel(false)
                    doneFuture(existingTransaction)
                }
            }
        }
    }

    @VisibleForTesting
    val transactions: List<SignedTransaction>
        get() = database.transaction { snapshot() }

    private fun snapshot(): List<SignedTransaction> {
        return txStorage.content.allPersisted.use {
            it.filter { it.second.status.isVerified() }.map { it.second.toSignedTx() }.toList()
        }
    }

    /**
     * returns a Paged Snapshot with give paging spec by querying transaction db
     */
    private fun pagedSnapshot(paging_: PageSpecification, totalTransactions: Long): TransactionStorage.Page {
        val paging = if (paging_.pageSize == Integer.MAX_VALUE) {
            paging_.copy(pageSize = Integer.MAX_VALUE - 1)
        } else {
            paging_
        }
        log.debug("Paging spec for transaction snapshot: $paging")
        // pagination checks
        if (!paging.isDefault) {
            // pagination
            if (paging.pageNumber < DEFAULT_PAGE_NUM) throw TransactionStorage.TransactionsQueryException("invalid page number ${paging.pageNumber} [page numbers start from $DEFAULT_PAGE_NUM]")
            if (paging.pageSize < 1) throw TransactionStorage.TransactionsQueryException("invalid page size ${paging.pageSize} [minimum is 1]")
        }
        //query
        val session = currentDBSession()
        val cb = session.criteriaBuilder
        val criteriaQuery = cb.createQuery(DBTransaction::class.java)
        val root = criteriaQuery.from(DBTransaction::class.java)
        criteriaQuery.select(root)
        criteriaQuery.orderBy(cb.desc(root.get<Instant>("timestamp")))
        val query = session.createQuery(criteriaQuery)

        query.firstResult = maxOf(0, (paging.pageNumber - 1) * paging.pageSize)
        val pageSize = paging.pageSize + 1
        query.maxResults = if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE// detection too many results, protected against overflow

        // final pagination check
        if (query.maxResults > MAX_PAGE_SIZE) {
            throw TransactionStorage.TransactionsQueryException("There are ${query.maxResults} results, which exceeds the limit of $MAX_PAGE_SIZE")
        }
        //execute
        val results = query.resultList
        val recordedTransactions: MutableList<TransactionStorage.RecordedTransaction> = mutableListOf()
        results.asSequence().forEachIndexed { index, dbTransaction ->
            if (index == paging.pageSize) // skip last result
                return@forEachIndexed
            recordedTransactions.add(TransactionStorage.RecordedTransaction(dbTransaction.transaction.deserialize(context = contextToUse()), dbTransaction.timestamp, dbTransaction.status.isVerified()))
        }
        return TransactionStorage.Page(recordedTransactions, totalTransactions)
    }

    // Cache value type to just store the immutable bits of a signed transaction plus conversion helpers
    private data class TxCacheValue(
            val txBits: SerializedBytes<CoreTransaction>,
            val sigs: List<TransactionSignature>,
            val status: TransactionStatus
    ) {
        constructor(stx: SignedTransaction, status: TransactionStatus) : this(
                stx.txBits,
                Collections.unmodifiableList(stx.sigs),
                status)

        fun toSignedTx() = SignedTransaction(txBits, sigs)
    }
}
