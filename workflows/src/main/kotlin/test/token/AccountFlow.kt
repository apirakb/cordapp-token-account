package test.token

import net.corda.core.flows.*
import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.utilities.getOrThrow
import net.corda.core.identity.Party


@StartableByRPC
@StartableByService
@InitiatingFlow
class CreateNewAccount(private val acctName:String) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        logger.info("=== Create new account: $acctName ===")
        //Create a new account
        val newAccount = accountService.createAccount(name = acctName).toCompletableFuture().getOrThrow()
        val acct = newAccount.state.data
        logger.info("=== Account: $acctName has been created successfully ===")
        return ""+acct.name + " account was created. UUID is : " + acct.identifier
    }
}

@StartableByRPC
@StartableByService
@InitiatingFlow
class QueryUUIDByAcctName(
        private val acctName: String
) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        logger.info("=== Search for account: $acctName ===")
        val acctInfo = accountService.ourAccounts().find { stateAndRef -> stateAndRef.state.data.name == acctName  }
                ?: throw IllegalArgumentException("Account $acctName not found")
        val acct = acctInfo.state.data
        return ""+acct.name + " LinearId is : " + acct.linearId
    }
}

@StartableByRPC
@StartableByService
@InitiatingFlow
class ShareAccountTo(
        private val acctName: String,
        private val shareTo: Party
        ) : FlowLogic<String>() {

    @Suspendable
    override fun call(): String {

        logger.info("=== Search for account: $acctName ===")
        val acctInfo = accountService.ourAccounts().find { stateAndRef -> stateAndRef.state.data.name == acctName  }
                ?: throw IllegalArgumentException("Account $acctName not found")
        val acct = acctInfo.state.data
        val stx = accountService.shareAccountInfoWithParty(acct.identifier.id, shareTo)
        return ""+acct.name + " has been shared to : " + shareTo.name.organisation
    }
}