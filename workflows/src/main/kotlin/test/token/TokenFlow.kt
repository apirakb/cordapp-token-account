package test.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStateAndRefs
import com.r3.corda.lib.tokens.contracts.utilities.withoutIssuer
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.heldTokenAmountCriteria
import com.r3.corda.lib.tokens.workflows.utilities.sumTokenCriteria
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.ProgressTracker
import test.token.contract.ZToken
import java.math.BigDecimal
import java.util.*

@InitiatingFlow
@StartableByService
@StartableByRPC
class CreateTokenFlow(
        private val symbol: String,
        private val valuation: Amount<Currency>,
        private val fractionDigits: Int
) : FlowLogic<String>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): String {

        logger.info("=== Request to create $symbol tokens type ===")
        logger.info("=== Check if tokens $symbol exist ===")

        val checkToken = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol  }

        if(checkToken != null)
            throw IllegalArgumentException("$symbol tokens type already exist")

        logger.info("=== Create $symbol tokens type ===")
        // get notary
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        // get own identity
        val issuer = ourIdentity
        // create token asset on ledger
        val token = ZToken(valuation = valuation, symbol = symbol, linearId = UniqueIdentifier(), maintainers = listOf(issuer), fractionDigits = fractionDigits)
        // create on ledger state object using transaction state which is a wrapper around token state
        val transactionState = TransactionState(token, notary = notary)
        // call built in CreateEvolvableToken flow to create token asset on ledger
        val stx = subFlow(CreateEvolvableTokens(transactionState))

        return "\n The $symbol token is created by '${issuer.name.organisation}' \n Transaction ID: '${stx.id}'"

    }
}

@InitiatingFlow
@StartableByService
@StartableByRPC
class IssueTokenFlow(
        private val symbol: String,
        private val quantity: Amount<BigDecimal>
) : FlowLogic<String>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): String {

        logger.info("=== Request to issue $quantity $symbol ===")

        val issuer = ourIdentity

        // query vault to get token state
        val tokenState = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol  }
                ?: throw IllegalArgumentException("$symbol tokens not found")

        val token = tokenState.state.data
        val issuedToken = IssuedTokenType(issuer, token.toPointer<ZToken>())
        val amount = Amount.fromDecimal(BigDecimal.valueOf(quantity.quantity, token.fractionDigits), issuedToken)
        val tokenToIssue = FungibleToken(amount, issuer, null)

        val stx = subFlow(IssueTokens(listOf(tokenToIssue)))


        val balance = serviceHub.vaultService.tokenBalance(token.toPointer<ZToken>())

        return "\n The $symbol token is created by '${issuer.name.organisation}' as quantity ${BigDecimal.valueOf(amount.quantity, amount.displayTokenSize.scale())}" +
                " balance ${BigDecimal.valueOf(balance.quantity, amount.displayTokenSize.scale())} '${symbol}' \n Transaction ID: '${stx.id}'"
    }
}

@InitiatingFlow
@StartableByService
@StartableByRPC
class DistributeTokenFlow(
        private val symbol: String,
        private val quantity: Amount<BigDecimal>,
        private val recipient: Party,
        private val toAccount: String
) : FlowLogic<String>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): String {

        logger.info("=== Distribute $quantity $symbol to ${recipient.name.organisation} ===")

        val distributor = ourIdentity
        if(distributor.name.organisation != "ZCentral")
            throw IllegalArgumentException("Distributor must be ZCentral")

        val acctInfo = accountService.accountInfo(toAccount).single().state.data
        val acctAnonymousParty = subFlow(RequestKeyForAccount(acctInfo))

        // query vault to get token state
        val tokenState = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol  }
                ?: throw IllegalArgumentException("$symbol tokens not found")

        val token = tokenState.state.data
        logger.info("=== token query $tokenState ===\n")
        val issuedToken = IssuedTokenType(distributor, token.toPointer<ZToken>())
        logger.info("=== issuedToken $issuedToken ===\n")
        val amount = Amount.fromDecimal<TokenType>(BigDecimal.valueOf(quantity.quantity, token.fractionDigits), issuedToken)
        logger.info("=== amount $amount ===\n")
        val partyAndAmount = PartyAndAmount(acctAnonymousParty, amount)


        val stx = subFlow(MoveFungibleTokens(partyAndAmount, emptyList(), null, distributor))

        logger.info("=== Input ${stx.coreTransaction.inputs} ===\n")

        logger.info("=== Output ${stx.coreTransaction.outRefsOfType<FungibleToken>()} ===\n")

        val balance = serviceHub.vaultService.tokenBalance(token.toPointer<ZToken>())

        return "\n The $symbol token is distributed to '$toAccount' - quantity ${BigDecimal.valueOf(amount.quantity, amount.displayTokenSize.scale())}" +
                " balance ${BigDecimal.valueOf(balance.quantity, amount.displayTokenSize.scale())} '${symbol}' \n Transaction ID: '${stx.id}'"


    }
}

@InitiatingFlow
@StartableByService
@StartableByRPC
class TransferTokenFlow(
        private val symbol: String,
        private val quantity: Amount<BigDecimal>,
        private val fromAccount: String,
        private val toAccount: String
) : FlowLogic<String>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): String {

        val bank = ourIdentity

        logger.info("=== Allocate $quantity $symbol to $toAccount by ${bank.name.organisation} ===")

        if(bank.name.organisation == "ZCentral")
            throw IllegalArgumentException("Allocator must not be ZCentral")

        val fromAcctInfo = accountService.ourAccounts().single { it.state.data.name == fromAccount }

        val toAcctInfo = accountService.ourAccounts().single { it.state.data.name == toAccount }

        // logger.info("=== Account UUID : ${toAcctInfo.state.data.identifier}")

        val fromAcctAnonymousParty = subFlow(RequestKeyForAccount(fromAcctInfo.state.data))


        val toAcctAnonymousParty = subFlow(RequestKeyForAccount(toAcctInfo.state.data))


        logger.info("=== Request Key For To-Account ${toAcctAnonymousParty.owningKey}")

        val tokenSymbolQuery = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol }
                ?: throw IllegalArgumentException("$symbol tokens not found")

        val tokenSymbolIdentifier = tokenSymbolQuery.state.data.linearId.toString()

        val tokenQuery = serviceHub.vaultService.queryBy<FungibleToken>(
                QueryCriteria.VaultQueryCriteria(externalIds = listOf(fromAcctInfo.state.data.identifier.id))).states
                .filter {
                    it.state.data.issuedTokenType.tokenIdentifier == tokenSymbolIdentifier}

        logger.info("=== Retrieved Token: $tokenQuery ===")
        val cbdcToken = tokenQuery.sumTokenStateAndRefs().token

        logger.info("=== Z Token: $cbdcToken ===")

        val amount = Amount.fromDecimal<TokenType>(BigDecimal.valueOf(quantity.quantity, cbdcToken.fractionDigits), cbdcToken)

        logger.info("=== Amount to move to $toAccount: $amount ===")

        val partyAndAmount = PartyAndAmount(toAcctAnonymousParty, amount)

        val stx = subFlow(MoveFungibleTokens(partyAndAmount, emptyList(), null, changeHolder = fromAcctAnonymousParty))

        logger.info("=== Input ${stx.inputs[0]} ===\n")

        logger.info("=== Output ${stx.coreTransaction.outRefsOfType<FungibleToken>()} ===\n")

        return "\n The $symbol token is allocated to '${toAccount}' as quantity ${BigDecimal.valueOf(amount.quantity, amount.displayTokenSize.scale())}"
    }
}


@StartableByRPC
@StartableByService
@InitiatingFlow
class QueryBalance(val symbol: String,
                   val acctName: String) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        val acctInfo = accountService.ourAccounts().single { it.state.data.name == acctName }.state.data



        val acctParty = subFlow(RequestKeyForAccount(acctInfo))


        val tokenSymbolQuery = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol }
                ?: throw IllegalArgumentException("$symbol tokens not found")

        val tokenSymbol = tokenSymbolQuery.state.data

        val tokenBalanceCriteria = heldTokenAmountCriteria(tokenSymbol.toPointer<ZToken>(), acctParty).and(sumTokenCriteria())

        val sum = serviceHub.vaultService.queryBy(FungibleToken::class.java, tokenBalanceCriteria).component5()

        return sum.toString()

    }
}

@StartableByRPC
@StartableByService
@InitiatingFlow
class QueryTokenByAcctName(val symbol: String,
                           val acctName: String) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        val acctInfo = accountService.ourAccounts().single { it.state.data.name == acctName }

        logger.info("=== Account UUID : ${acctInfo.state.data.identifier}")

        val acctId = acctInfo.state.data.identifier.id


        val tokenSymbolQuery = serviceHub.vaultService.queryBy<ZToken>().states.find { stateAndRef -> stateAndRef.state.data.symbol == symbol }
                ?: throw IllegalArgumentException("$symbol tokens not found")

        val tokenSymbolIdentifier = tokenSymbolQuery.state.data.linearId.toString()

        val tokenQuery = serviceHub.vaultService.queryBy<FungibleToken>(QueryCriteria.VaultQueryCriteria(externalIds = listOf(acctId))).states
                .filter { it.state.data.issuedTokenType.tokenIdentifier == tokenSymbolIdentifier}

        logger.info("=== Retrieved tokens ${tokenQuery} ===")

        logger.info("=== Retrieved tokens ${tokenQuery.sumTokenStateAndRefs().withoutIssuer()} ===")

        //if(acctQuery.states.isNotEmpty())
        val balance = tokenQuery.sumTokenStateAndRefs().withoutIssuer()


        return "\n Account '${acctName}' currently have ${BigDecimal.valueOf(balance.quantity, balance.displayTokenSize.scale())} $symbol Tokens"
    }
}