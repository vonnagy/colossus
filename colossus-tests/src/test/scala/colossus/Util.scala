package colossus

import core._

import java.net.InetSocketAddress
import service.{Codec, AsyncServiceClient, ClientConfig}

import akka.actor._
import akka.pattern.ask
import akka.testkit.TestProbe

import akka.util.{ByteString, Timeout}
import java.net.{SocketException, Socket}

import scala.concurrent.Await
import scala.concurrent.duration._

class EchoHandler extends BasicSyncHandler {
  def receivedData(data: DataBuffer){
    endpoint.write(data)
  }
}

object RawProtocol {
  import service._

  object RawCodec extends Codec[ByteString, ByteString] {
    def decode(data: DataBuffer) = Some(ByteString(data.takeAll))
    def encode(raw: ByteString) = DataBuffer(raw)
    def reset(){}
  }

  trait Raw extends CodecDSL {
    type Input = ByteString
    type Output = ByteString
  }

  implicit object RawCodecProvider extends CodecProvider[Raw] {
    def provideCodec() = RawCodec

    def errorResponse(request: ByteString, reason: Throwable) = ByteString(s"Error (${reason.getClass.getName}): ${reason.getMessage}")
  }

  implicit object RawClientCodecProvider extends ClientCodecProvider[Raw] {
    def clientCodec() = RawCodec
    val name = "raw"
  }

}

object TestClient {

  def apply(io: IOSystem, port: Int, waitForConnected: Boolean = true, reconnect: Boolean = true): AsyncServiceClient[ByteString, ByteString] = {
    val config = ClientConfig(
      name = "/test",
      requestTimeout = 100.milliseconds,
      address = new InetSocketAddress("localhost", port),
      pendingBufferSize = 0,
      failFast = true,
      autoReconnect = reconnect
    )
    val client = AsyncServiceClient(config, RawProtocol.RawCodec)(io)
    if (waitForConnected) {
      TestClient.waitForConnected(client)
    }
    client
  }

  def waitForConnected[I,O](client: AsyncServiceClient[I,O], maxTries: Int = 5) {
    waitForStatus(client, ConnectionStatus.Connected, maxTries)
  }

  def waitForStatus[I,O](client: AsyncServiceClient[I, O], status: ConnectionStatus, maxTries: Int = 5) {
    var tries = maxTries
    var last = Await.result(client.connectionStatus, 500.milliseconds)
    while (last != status) {
      Thread.sleep(50)
      tries -= 1
      if (tries == 0) {
        throw new Exception(s"Test client failed to achieve status $status, last status was $last")
      }
      last = Await.result(client.connectionStatus, 500.milliseconds)
    }
  }

}


object TestUtil {
  def expectServerConnections(server: ServerRef, connections: Int, maxTries: Int = 5) {
    var tries = maxTries
    implicit val timeout = Timeout(100.milliseconds)
    while (Await.result((server.server ? Server.GetInfo), 100.milliseconds) != Server.ServerInfo(connections, ServerStatus.Bound)) {
      Thread.sleep(50)
      tries -= 1
      if (tries == 0) {
        throw new Exception(s"Server failed to achieve $connections connections")
      }
    }

  }

}
