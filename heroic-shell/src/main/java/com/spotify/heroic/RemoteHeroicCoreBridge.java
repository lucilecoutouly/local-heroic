package com.spotify.heroic;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import com.spotify.heroic.shell.CommandDefinition;
import com.spotify.heroic.shell.CoreInterface;
import com.spotify.heroic.shell.CoreShellInterface;
import com.spotify.heroic.shell.ShellInterface;
import com.spotify.heroic.shell.ShellProtocol;
import com.spotify.heroic.shell.ShellProtocol.CommandOutput;
import com.spotify.heroic.shell.ShellProtocol.CommandsRequest;
import com.spotify.heroic.shell.ShellProtocol.CommandsResponse;
import com.spotify.heroic.shell.ShellProtocol.EndOfCommand;
import com.spotify.heroic.shell.ShellProtocol.RunTaskRequest;
import com.spotify.heroic.shell.ShellServerModule;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.SerialWriter;
import eu.toolchain.serializer.Serializer;
import eu.toolchain.serializer.io.InputStreamSerialReader;
import eu.toolchain.serializer.io.OutputStreamSerialWriter;

@Slf4j
public class RemoteHeroicCoreBridge implements CoreInterface {
    final Socket socket;
    final AsyncFramework async;
    final OutputStream out;
    final SerialWriter output;
    final SerialReader input;

    public RemoteHeroicCoreBridge(Socket socket, AsyncFramework async) throws IOException {
        this.socket = socket;
        this.async = async;
        this.out = socket.getOutputStream();
        this.output = new OutputStreamSerialWriter(out);
        this.input = new InputStreamSerialReader(socket.getInputStream());
    }

    final ShellProtocol protocol = new ShellProtocol();

    @Override
    public CoreShellInterface setup(final ShellInterface shell) throws Exception {
        return new CoreShellInterface() {
            @Override
            public AsyncFuture<Void> command(final List<String> command, final PrintWriter out) throws Exception {
                return async.call(() -> {
                    protocol.request.serialize(output, new RunTaskRequest(command));
                    out.flush();

                    final Serializer<ShellProtocol.Response> res = protocol.message;

                    while (true) {
                        final ShellProtocol.Response response = res.deserialize(input);

                        if (response instanceof EndOfCommand) {
                            break;
                        }

                        if (response instanceof CommandOutput) {
                            handleCommandOutput((CommandOutput) response, out);
                            continue;
                        }

                        log.error("Unhandled response: {}", response);
                    }

                    return null;
                });
            }

            private void handleCommandOutput(CommandOutput response, PrintWriter out) {
                out.write(response.getData());
                out.flush();
            }
        };
    }

    @Override
    public List<CommandDefinition> getCommands() throws Exception {
        return request(new CommandsRequest(), CommandsResponse.class).getCommands();
    }

    @SuppressWarnings("unchecked")
    <R extends ShellProtocol.Request, T extends ShellProtocol.Response> T request(R request,
            Class<T> expected) throws IOException {
        protocol.request.serialize(output, request);
        out.flush();

        final ShellProtocol.Response response = protocol.message.deserialize(input);

        if (!(expected.isAssignableFrom(response.getClass()))) {
            throw new IOException(String.format("Got unexpected message (%s), expected (%s)", response.getClass(),
                    expected));
        }

        return (T) response;
    }

    @Override
    public void shutdown() throws Exception {
        socket.close();
    }

    public static RemoteHeroicCoreBridge fromConnectString(String connect, AsyncFramework async) throws IOException {
        final String host;
        final int port;

        final int index;

        if ((index = connect.indexOf(':')) > 0) {
            host = connect.substring(0, index);
            port = Integer.valueOf(connect.substring(index + 1));
        } else {
            host = connect;
            port = ShellServerModule.DEFAULT_PORT;
        }

        return fromInetSocketAddress(new InetSocketAddress(host, port), async);
    }

    private static RemoteHeroicCoreBridge fromInetSocketAddress(InetSocketAddress address, AsyncFramework async)
            throws IOException {
        final Socket socket = new Socket();
        socket.connect(address);
        return new RemoteHeroicCoreBridge(socket, async);
    }
}