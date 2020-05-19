package test.token.contract

import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.contracts.Amount
import net.corda.core.schemas.StatePersistable
import java.util.Currency

@BelongsToContract(ZContract::class)
data class ZToken(val valuation: Amount<Currency>,
                  val symbol: String,
                  override val maintainers: List<Party>,
                  override val linearId: UniqueIdentifier,
                  override val fractionDigits: Int = 2
): EvolvableTokenType(), StatePersistable {

    val issuer: Party get() = maintainers[0]

}

