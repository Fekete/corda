package net.corda.node.services.transactions

import net.corda.core.crypto.Party
import net.corda.core.node.services.TimestampChecker
import net.corda.node.services.api.ServiceHubInternal
import net.corda.protocols.ValidatingNotaryProtocol

/** A validating notary service operated by a group of mutually trusting parties, uses the Raft algorithm to achieve consensus. */
class RaftValidatingNotaryService(services: ServiceHubInternal,
                                  val timestampChecker: TimestampChecker,
                                  val uniquenessProvider: RaftUniquenessProvider) : NotaryService(services) {
    companion object {
        val type = ValidatingNotaryService.type.getSubType("raft")
    }

    override fun createProtocol(otherParty: Party): ValidatingNotaryProtocol {
        return ValidatingNotaryProtocol(otherParty, timestampChecker, uniquenessProvider)
    }
}
