package net.corda.node.services.events

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.SchedulableState
import net.corda.core.contracts.ScheduledStateRef
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.commonName
import net.corda.core.utilities.loggerFor
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.statemachine.FlowLogicRefFactoryImpl

/**
 * This observes the vault and schedules and unschedules activities appropriately based on state production and
 * consumption.
 */
class ScheduledActivityObserver(val services: ServiceHubInternal) {

    private val logger = loggerFor<ScheduledActivityObserver>()

    init {
        services.vaultService.rawUpdates.subscribe { (consumed, produced) ->
            consumed.forEach {
                logger.info("${services.myInfo.legalIdentity.name.commonName} : unscheduleStateActivity ${it.ref.txhash}")
                services.schedulerService.unscheduleStateActivity(it.ref)
            }
            produced.forEach {
                logger.info("${services.myInfo.legalIdentity.name.commonName} : scheduleStateActivity ${it.ref.txhash}")
                scheduleStateActivity(it)
            }
        }
    }

    private fun scheduleStateActivity(produced: StateAndRef<ContractState>) {
        val producedState = produced.state.data
        if (producedState is SchedulableState) {
            val scheduledAt = sandbox { producedState.nextScheduledActivity(produced.ref, FlowLogicRefFactoryImpl)?.scheduledAt } ?: return
            services.schedulerService.scheduleStateActivity(ScheduledStateRef(produced.ref, scheduledAt))
        }
    }

    // TODO: Beware we are calling dynamically loaded contract code inside here.
    private inline fun <T : Any> sandbox(code: () -> T?): T? {
        return code()
    }
}
