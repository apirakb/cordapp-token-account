package test.token.contract

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction


// ************
// * Contract *
// ************
class ZContract : EvolvableTokenContract(), Contract {

    override fun additionalCreateChecks(tx: LedgerTransaction) = requireThat {
        val output = tx.outputsOfType<ZToken>().first()
        "Issuer must be ZCentral, but found '${output.issuer.name.organisation}'" using (output.issuer.name.organisation == "ZCentral")
    }

    override fun additionalUpdateChecks(tx: LedgerTransaction) = Unit
}