package net.corda.nodeapi.internal.cryptoservice

import net.corda.core.identity.CordaX500Name
import net.corda.nodeapi.internal.config.FileBasedCertificateStoreSupplier
import net.corda.nodeapi.internal.cryptoservice.azure.AzureKeyVaultCryptoService
import net.corda.nodeapi.internal.cryptoservice.bouncycastle.BCCryptoService
import java.time.Duration
import net.corda.nodeapi.internal.cryptoservice.futurex.FutureXCryptoService
import net.corda.nodeapi.internal.cryptoservice.gemalto.GemaltoLunaCryptoService
import net.corda.nodeapi.internal.cryptoservice.securosys.PrimusXCryptoService
import net.corda.nodeapi.internal.cryptoservice.utimaco.UtimacoCryptoService
import java.nio.file.Path

class CryptoServiceFactory {
    companion object {
        fun makeCryptoService(
                cryptoServiceName: SupportedCryptoServices,
                legalName: CordaX500Name,
                signingCertificateStore: FileBasedCertificateStoreSupplier? = null,
                cryptoServiceConf: Path? = null,
                timeout: Duration? = null
        ): CryptoService {
            // The signing certificate store can be null for other services as only BCC requires is at the moment.
            return when (cryptoServiceName) {
                SupportedCryptoServices.BC_SIMPLE -> {
                    if (signingCertificateStore == null) {
                        throw IllegalArgumentException("A valid signing certificate store is required to create a BouncyCastle crypto service.")
                    }
                    BCCryptoService(legalName.x500Principal, signingCertificateStore)
                }
                SupportedCryptoServices.UTIMACO -> UtimacoCryptoService.fromConfigurationFile(cryptoServiceConf, timeout)
                SupportedCryptoServices.GEMALTO_LUNA -> GemaltoLunaCryptoService.fromConfigurationFile(legalName.x500Principal, cryptoServiceConf, timeout)
                SupportedCryptoServices.AZURE_KEY_VAULT -> {
                    val configPath = requireNotNull(cryptoServiceConf) { "When cryptoServiceName is set to AZURE_KEY_VAULT, cryptoServiceConf must specify the path to the configuration file."}
                    AzureKeyVaultCryptoService.fromConfigurationFile(configPath, timeout)
                }
                SupportedCryptoServices.FUTUREX -> FutureXCryptoService.fromConfigurationFile(legalName.x500Principal, cryptoServiceConf, timeout)
                SupportedCryptoServices.PRIMUS_X -> PrimusXCryptoService.fromConfigurationFile(legalName.x500Principal, cryptoServiceConf, timeout)
            }
        }
    }
}