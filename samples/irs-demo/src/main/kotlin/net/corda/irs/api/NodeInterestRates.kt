package net.corda.irs.api

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.BusinessCalendar
import net.corda.contracts.Fix
import net.corda.contracts.FixOf
import net.corda.contracts.Tenor
import net.corda.contracts.math.CubicSplineInterpolator
import net.corda.contracts.math.Interpolator
import net.corda.contracts.math.InterpolatorFactory
import net.corda.core.contracts.Command
import net.corda.core.crypto.*
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.internal.ThreadBox
import net.corda.core.node.PluginServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceType
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.irs.flows.RatesFixFlow
import org.apache.commons.io.IOUtils
import java.math.BigDecimal
import java.security.PublicKey
import java.time.LocalDate
import java.util.*
import javax.annotation.concurrent.ThreadSafe
import kotlin.collections.HashSet
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

/**
 * An interest rates service is an oracle that signs transactions which contain embedded assertions about an interest
 * rate fix (e.g. LIBOR, EURIBOR ...).
 *
 * The oracle has two functions. It can be queried for a fix for the given day. And it can sign a transaction that
 * includes a fix that it finds acceptable. So to use it you would query the oracle, incorporate its answer into the
 * transaction you are building, and then (after possibly extra steps) hand the final transaction back to the oracle
 * for signing.
 */
object NodeInterestRates {
    // DOCSTART 2
    @InitiatedBy(RatesFixFlow.FixSignFlow::class)
    class FixSignHandler(val otherParty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val request = receive<RatesFixFlow.SignRequest>(otherParty).unwrap { it }
            val oracle = serviceHub.cordaService(Oracle::class.java)
            send(otherParty, oracle.sign(request.ftx))
        }
    }

    @InitiatedBy(RatesFixFlow.FixQueryFlow::class)
    class FixQueryHandler(val otherParty: Party) : FlowLogic<Unit>() {
        object RECEIVED : ProgressTracker.Step("Received fix request")
        object SENDING : ProgressTracker.Step("Sending fix response")

        override val progressTracker = ProgressTracker(RECEIVED, SENDING)

        @Suspendable
        override fun call(): Unit {
            val request = receive<RatesFixFlow.QueryRequest>(otherParty).unwrap { it }
            progressTracker.currentStep = RECEIVED
            val oracle = serviceHub.cordaService(Oracle::class.java)
            val answers = oracle.query(request.queries)
            progressTracker.currentStep = SENDING
            send(otherParty, answers)
        }
    }
    // DOCEND 2

    /**
     * An implementation of an interest rate fix oracle which is given data in a simple string format.
     *
     * The oracle will try to interpolate the missing value of a tenor for the given fix name and date.
     */
    @ThreadSafe
    // DOCSTART 3
    @CordaService
    class Oracle(val identity: Party, private val signingKey: PublicKey, val services: ServiceHub) : SingletonSerializeAsToken() {
        constructor(services: PluginServiceHub) : this(
            services.myInfo.serviceIdentities(type).first(),
            services.myInfo.serviceIdentities(type).first().owningKey.keys.first { services.keyManagementService.keys.contains(it) },
            services
        ) {
            // Set some default fixes to the Oracle, so we can smoothly run the IRS Demo without uploading fixes.
            // This is required to avoid a situation where the runnodes version of the demo isn't in a good state
            // upon startup.
            addDefaultFixes()
        }
        // DOCEND 3

        companion object {
            @JvmField
            val type = ServiceType.corda.getSubType("interest_rates")
        }

        private class InnerState {
            // TODO Update this to use a database once we have an database API
            val fixes = HashSet<Fix>()
            var container: FixContainer = FixContainer(fixes)
        }

        private val mutex = ThreadBox(InnerState())

        var knownFixes: FixContainer
            set(value) {
                require(value.size > 0)
                mutex.locked {
                    fixes.clear()
                    fixes.addAll(value.fixes)
                    container = value
                }
            }
            get() = mutex.locked { container }

        // Make this the last bit of initialisation logic so fully constructed when entered into instances map
        init {
            require(signingKey in identity.owningKey.keys)
        }

        @Suspendable
        fun query(queries: List<FixOf>): List<Fix> {
            require(queries.isNotEmpty())
            return mutex.locked {
                val answers: List<Fix?> = queries.map { container[it] }
                val firstNull = answers.indexOf(null)
                if (firstNull != -1) {
                    throw UnknownFix(queries[firstNull])
                } else {
                    answers.filterNotNull()
                }
            }
        }

        // TODO There is security problem with that. What if transaction contains several commands of the same type, but
        //      Oracle gets signing request for only some of them with a valid partial tree? We sign over a whole transaction.
        //      It will be fixed by adding partial signatures later.
        // DOCSTART 1
        fun sign(ftx: FilteredTransaction): TransactionSignature {
            if (!ftx.verify()) {
                throw MerkleTreeException("Rate Fix Oracle: Couldn't verify partial Merkle tree.")
            }
            // Performing validation of obtained FilteredLeaves.
            fun commandValidator(elem: Command<*>): Boolean {
                if (!(identity.owningKey in elem.signers && elem.value is Fix))
                    throw IllegalArgumentException("Oracle received unknown command (not in signers or not Fix).")
                val fix = elem.value as Fix
                val known = knownFixes[fix.of]
                if (known == null || known != fix)
                    throw UnknownFix(fix.of)
                return true
            }

            fun check(elem: Any): Boolean {
                return when (elem) {
                    is Command<*> -> commandValidator(elem)
                    else -> throw IllegalArgumentException("Oracle received data of different type than expected.")
                }
            }

            val leaves = ftx.filteredLeaves
            if (!leaves.checkWithFun(::check))
                throw IllegalArgumentException()

            // It all checks out, so we can return a signature.
            //
            // Note that we will happily sign an invalid transaction, as we are only being presented with a filtered
            // version so we can't resolve or check it ourselves. However, that doesn't matter much, as if we sign
            // an invalid transaction the signature is worthless.
            val signableData = SignableData(ftx.rootHash, SignatureMetadata(services.myInfo.platformVersion, Crypto.findSignatureScheme(signingKey).schemeNumberID))
            val signature = services.keyManagementService.sign(signableData, signingKey)
            return TransactionSignature(signature.bytes, signingKey, signableData.signatureMetadata)
        }
        // DOCEND 1

        fun uploadFixes(s: String) {
            knownFixes = parseFile(s)
        }

        private fun addDefaultFixes() {
            knownFixes = parseFile(IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("net/corda/irs/simulation/example.rates.txt"), Charsets.UTF_8.name()))
        }
    }

    // TODO: can we split into two?  Fix not available (retryable/transient) and unknown (permanent)
    class UnknownFix(val fix: FixOf) : FlowException("Unknown fix: $fix")

    // Upload the raw fix data via RPC. In a real system the oracle data would be taken from a database.
    @StartableByRPC
    class UploadFixesFlow(val s: String) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() = serviceHub.cordaService(Oracle::class.java).uploadFixes(s)
    }

    /** Fix container, for every fix name & date pair stores a tenor to interest rate map - [InterpolatingRateMap] */
    class FixContainer(val fixes: Set<Fix>, val factory: InterpolatorFactory = CubicSplineInterpolator) {
        private val container = buildContainer(fixes)
        val size: Int get() = fixes.size

        operator fun get(fixOf: FixOf): Fix? {
            val rates = container[fixOf.name to fixOf.forDay]
            val fixValue = rates?.getRate(fixOf.ofTenor) ?: return null
            return Fix(fixOf, fixValue)
        }

        private fun buildContainer(fixes: Set<Fix>): Map<Pair<String, LocalDate>, InterpolatingRateMap> {
            val tempContainer = HashMap<Pair<String, LocalDate>, HashMap<Tenor, BigDecimal>>()
            for ((fixOf, value) in fixes) {
                val rates = tempContainer.getOrPut(fixOf.name to fixOf.forDay) { HashMap<Tenor, BigDecimal>() }
                rates[fixOf.ofTenor] = value
            }

            // TODO: the calendar data needs to be specified for every fix type in the input string
            val calendar = BusinessCalendar.getInstance("London", "NewYork")

            return tempContainer.mapValues { InterpolatingRateMap(it.key.second, it.value, calendar, factory) }
        }
    }

    /**
     * Stores a mapping between tenors and interest rates.
     * Interpolates missing values using the provided interpolation mechanism.
     */
    class InterpolatingRateMap(val date: LocalDate,
                               inputRates: Map<Tenor, BigDecimal>,
                               val calendar: BusinessCalendar,
                               val factory: InterpolatorFactory) {

        /** Snapshot of the input */
        private val rates = HashMap(inputRates)

        /** Number of rates excluding the interpolated ones */
        val size = inputRates.size

        private val interpolator: Interpolator? by lazy {
            // Need to convert tenors to doubles for interpolation
            val numericMap = rates.mapKeys { daysToMaturity(it.key) }.toSortedMap()
            val keys = numericMap.keys.map { it.toDouble() }.toDoubleArray()
            val values = numericMap.values.map { it.toDouble() }.toDoubleArray()

            try {
                factory.create(keys, values)
            } catch (e: IllegalArgumentException) {
                null // Not enough data points for interpolation
            }
        }

        /**
         * Returns the interest rate for a given [Tenor],
         * or _null_ if the rate is not found and cannot be interpolated.
         */
        fun getRate(tenor: Tenor): BigDecimal? {
            return rates.getOrElse(tenor) {
                val rate = interpolate(tenor)
                if (rate != null) rates.put(tenor, rate)
                return rate
            }
        }

        private fun daysToMaturity(tenor: Tenor) = tenor.daysToMaturity(date, calendar)

        private fun interpolate(tenor: Tenor): BigDecimal? {
            val key = daysToMaturity(tenor).toDouble()
            val value = interpolator?.interpolate(key) ?: return null
            return BigDecimal(value)
        }
    }

    /** Parses lines containing fixes */
    fun parseFile(s: String): FixContainer {
        val fixes = s.lines().
                map(String::trim).
                // Filter out comment and empty lines.
                filterNot { it.startsWith("#") || it.isBlank() }.
                map(this::parseFix).
                toSet()
        return FixContainer(fixes)
    }

    /** Parses a string of the form "LIBOR 16-March-2016 1M = 0.678" into a [Fix] */
    private fun parseFix(s: String): Fix {
        try {
            val (key, value) = s.split('=').map(String::trim)
            val of = parseFixOf(key)
            val rate = BigDecimal(value)
            return Fix(of, rate)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to parse fix $s: ${e.message}", e)
        }
    }

    /** Parses a string of the form "LIBOR 16-March-2016 1M" into a [FixOf] */
    fun parseFixOf(key: String): FixOf {
        val words = key.split(' ')
        val tenorString = words.last()
        val date = words.dropLast(1).last()
        val name = words.dropLast(2).joinToString(" ")
        return FixOf(name, LocalDate.parse(date), Tenor(tenorString))
    }
}
