package net.corda.jmeter

import net.corda.client.rpc.CordaRPCClient
import net.corda.client.rpc.CordaRPCConnection
import net.corda.core.flows.FlowLogic
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.NetworkHostAndPort
import org.apache.jmeter.config.Argument
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext
import org.apache.jmeter.samplers.SampleResult


class CordaRPCSampler() : AbstractJavaSamplerClient() {
    companion object {
        val host = Argument("host", "localhost", "<meta>", "The remote network address (hostname or IP address) to connect to for RPC.")
        val port = Argument("port", "10000", "<meta>", "The remote port to connect to for RPC.")
        val username = Argument("username", "corda", "<meta>", "The RPC user to connect to connect as.")
        val password = Argument("password", "corda_is_awesome", "<meta>", "The password for the RPC user.")
        val className = Argument("pluginClassName", "", "<meta>", "The class name of the implementation of ${CordaRPCSampler.Plugin::class.java}.")

        val allArgs = setOf(host, port, username, password, className)
    }

    var rpcClient: CordaRPCClient? = null
    var rpcConnection: CordaRPCConnection? = null
    var rpcProxy: CordaRPCOps? = null
    var plugin: Plugin? = null

    override fun getDefaultParameters(): Arguments {
        // Add copies of all args, since they seem to be mutable.
        return Arguments().apply { for(arg in allArgs) { addArgument(arg.clone() as Argument) } }
    }

    override fun setupTest(context: JavaSamplerContext) {
        super.setupTest(context)
        rpcClient = CordaRPCClient(NetworkHostAndPort(context.getParameter(host.name), context.getIntParameter(port.name)))
        rpcConnection = rpcClient!!.start(context.getParameter(username.name), context.getParameter(password.name))
        rpcProxy = rpcConnection!!.proxy
        plugin = Class.forName(context.getParameter(className.name)).newInstance() as Plugin
        plugin!!.setupTest(rpcProxy!!, context)
    }

    override fun runTest(context: JavaSamplerContext): SampleResult {
        val flowInvoke = plugin!!.createFlowInvoke(rpcProxy!!, context)
        val result = SampleResult()
        result.sampleStart()
        val handle = rpcProxy!!.startFlowDynamic(flowInvoke!!.flowLogicClass, *(flowInvoke!!.args))
        result.sampleLabel = handle.id.toString()
        result.latencyEnd()
        try {
            val flowResult = handle.returnValue.get()
            result.sampleEnd()
            return result.apply {
                isSuccessful = true
            }
        } catch(e: Exception) {
            result.sampleEnd()
            return result.apply {
                isSuccessful = false
            }
        }
    }

    override fun teardownTest(context: JavaSamplerContext) {
        plugin!!.teardownTest(rpcProxy!!, context)
        plugin = null
        rpcProxy = null
        rpcConnection!!.close()
        rpcConnection = null
        rpcClient = null
        super.teardownTest(context)
    }

    interface Plugin {
        fun setupTest(rpcProxy: CordaRPCOps, testContext: JavaSamplerContext)
        fun createFlowInvoke(rpcProxy: CordaRPCOps, testContext: JavaSamplerContext): FlowInvoke<*>
        fun teardownTest(rpcProxy: CordaRPCOps, testContext: JavaSamplerContext)
    }

    class FlowInvoke<T : FlowLogic<*>>(val flowLogicClass: Class<out T>, val args: Array<Any?>)
}