/*
 * Copyright (c) 2020, The Magma Authors
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Foundation
import GRPC
import NIO
import NIOSSL
import SwiftProtobuf
import Logging

/// This class handles the bootstrap process to create a GRPC connection to Magma server and get a Signed certificate from it to be able to strat pushing metrics to Magma.
public class BootstrapManager {
    
    ///Shared clientConfig class singleton instance.
    private let clientConfig = ClientConfig.shared
    ///Shared UUIDManager class singleton instance.
    private let uuidManager = UUIDManager.shared
    ///Shared wifiNetworkInfo singleton instance.
    private let wifiNetworkinfo = WifiNetworkInfo.shared
    ///KeyHelper class instance.
    private let keyHelper = KeyHelper()
    ///CertSignRequest class instance.
    private let certSignRequest = CertSignRequest()
    ///String that contains the path to the certificate to be used for connecting to the server on Bootstrap.
    private var certificateFilePath : String
    
    ///Initialize Bootstrap Class, it requires the path to the server certificate for Bootstrap.
    public init(certificateFilePath : String) {
        self.certificateFilePath = certificateFilePath
        print(self.uuidManager.getUUID())
        CreateSSIDObserver()
    }
    
    ///This function creates an observer that detects if the Wi-Fi changed since last time app using the framework was on foregorund.
     if the Wi-Fi information is different BootstrapNow function is called */
    private func CreateSSIDObserver() {
        let observer : UnsafeRawPointer! = UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque())
        let object : UnsafeRawPointer! = nil
        
        let callback: CFNotificationCallback = { center, observer, name, object, info in
            print("Wi-Fi SSID name changed")
            
            let mySelf = Unmanaged<BootstrapManager>.fromOpaque(UnsafeRawPointer(observer!)).takeUnretainedValue()
            // Call instance method:
            mySelf.wifiNetworkinfo.fetchSSIDInfo()
            mySelf.BootstrapNow()

        }

        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(),
                                        observer,
                                        callback,
                                        "com.apple.system.config.network_change" as CFString,
                                        object,
                                        .deliverImmediately)
    }
    
    ///This function calls BootstrapLogic and sends it to a background thread to prevent locking the UI during its execution.
    public func BootstrapNow(){
        let dispatchQueue = DispatchQueue(label: "QueueIdentification", qos: .background)
        dispatchQueue.async{
            self.BootstrapLogic()
        }
    }
    
    ///This creates a GRPC channel and tries to connect to the specified server using the certificate provided on class init. If connection is succesful a connection is created and MetricsManager CollectAndPushMetrics function is called.
    private func BootstrapLogic() {

        do {
            let pemCert = try NIOSSLCertificate.fromPEMFile(certificateFilePath)
            
            //Step ii: Create an event loop group
            let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
            
            // Setup a logger for debugging.
            var logger = Logger(label: "gRPC", factory: StreamLogHandler.standardOutput(label:))
            logger.logLevel = .debug
            
            //Step iii: Create client connection builder
            let builder: ClientConnection.Builder
            builder = ClientConnection.secure(group: group).withTLS(trustRoots: .certificates(pemCert)).withBackgroundActivityLogger(logger)
            
            //Step iv: Start the connection and create the client
            let connection = builder.connect(host: self.clientConfig.getBootstrapControllerAddress(), port: self.clientConfig.getControllerPort())
            print("Bootstrapper Connection Status=>: \(connection)")
            
            //Step v: Create client
            //use appropriate service client from .grpc server to replace the xxx call : <your .grpc.swift ServiceClient> = <XXX>ServiceClient
            let client: Magma_Orc8r_BootstrapperClient = Magma_Orc8r_BootstrapperClient(channel: connection)
            
            //Step vi: Call specific service request
            let accessGateWayID = Magma_Orc8r_AccessGatewayID.with {
                $0.id = uuidManager.getUUID()
            }

            // Make the RPC call to the server.
            let hardwareKey = HardwareKEY()
            print("Private ECDSA Key: " + hardwareKey.getHwPrivateKeyPEMString())
            print("Public ECDSA Key: " + hardwareKey.getHwPublicKeyPEMString())
            
            let challenge = client.getChallenge(accessGateWayID)

            challenge.response.whenComplete { result in
                
                print("Output for get request: \(result)")
                
                do {
                    
                    let challengeResult = try result.get()
                    
                    let signature = try challengeResult.challenge.sign(with: hardwareKey.getHwPrivateKey())
                    
                    let ecdsaResponse = Magma_Orc8r_Response.ECDSA.with {
                        $0.r = signature.r
                        $0.s = signature.s
                    }

                    self.keyHelper.DeleteKeyFromKeyChain(alias: "csrKeyPrivate", keyType: kSecAttrKeyTypeRSA)
                    self.keyHelper.DeleteKeyFromKeyChain(alias: "csrKeyPublic", keyType: kSecAttrKeyTypeRSA)
                    self.keyHelper.generateRSAKeyPairForAlias(alias: "csrKey")
                    
                    print("Private RSA Key: " + self.keyHelper.getKeyAsBase64String(alias: "csrKeyPrivate", keyType: kSecAttrKeyTypeRSA))
                    print("Public RSA Key: " + self.keyHelper.getKeyAsBase64String(alias: "csrKeyPublic", keyType: kSecAttrKeyTypeRSA))
                    
                    print("RSA private Key for CSR: " + NSData(data: self.keyHelper.getKeyAsData(alias : "csrKeyPrivate", keyType: kSecAttrKeyTypeRSA)).base64EncodedString())
                    print("csr: " + self.certSignRequest.getCSRString())
 
                    let csrMagma = Magma_Orc8r_CSR.with {
                        $0.certType = Magma_Orc8r_CertType(rawValue: 0)!
                        $0.id = Magma_Orc8r_Identity.with {
                            $0.gateway = Magma_Orc8r_Identity.Gateway.with {
                                $0.hardwareID = self.uuidManager.getUUID()
                            }
                        }
                        $0.validTime = SwiftProtobuf.Google_Protobuf_Duration.init(seconds: 10000, nanos: 10000)
                        $0.csrDer = self.certSignRequest.getBuiltCSR()
                    }
                    
                    let response = Magma_Orc8r_Response.with {
                        $0.hwID = accessGateWayID
                        $0.challenge = challengeResult.challenge
                        $0.ecdsaResponse = ecdsaResponse
                        $0.csr = csrMagma
                    }
                    
                    let challengeResponse = client.requestSign(response)
                    
                    challengeResponse.response.whenComplete { result in
                        
                        print("Output for Challenge Response request: \(result)")
                        
                        do {
                            let signedCertData = try result.get().certDer
                            
                            let metricsManager = MetricsManager(signedCert: signedCertData, certificateFilePath: self.certificateFilePath)
                            metricsManager.CollectAndPushMetrics()
                            
                            
                        } catch {

                            print("Error retrieving Signed Cert Data: \(error)")
                        }
                        

                    }
                    
                    challengeResponse.response.whenFailure { error in
                        print("Output for Challenge Response failed request: \(error)")
                    }
                    
                } catch {
                    print("Error for get Request:\(error)")
                }
    
            }
            
            challenge.response.whenFailure { error in
                print("Output for failed request: \(error)")
            }
            
            do {
                let detailsStatus = try challenge.status.wait()
                print("Status:::\(detailsStatus) \n \(detailsStatus.code))")
            } catch {
                print("Error for get Request:\(error)")
            }
            
        } catch {
            print("Error getting certificate: \(error)")
        }
    }
}
