/**
 * Typed shim for the browser-only build of stompjs.
 * Importing 'stompjs' (index.js) requires node's 'net' module, which breaks
 * browser bundling - so the app imports 'stompjs/lib/stomp.js' directly.
 */
declare module 'stompjs/lib/stomp.js' {
  export interface Frame {
    command: string;
    headers: { [key: string]: string };
    body: string;
  }

  export interface Subscription {
    id: string;
    unsubscribe(): void;
  }

  export class Client {
    connected: boolean;
    connect(
      headers: { [key: string]: string },
      connectCallback?: (frame?: Frame) => any,
      errorCallback?: (error: Frame | string) => any
    ): any;
    disconnect(disconnectCallback?: () => any, headers?: { [key: string]: string }): any;
    send(destination: string, headers?: { [key: string]: string }, body?: string): any;
    subscribe(
      destination: string,
      callback?: (message: any) => any,
      headers?: { [key: string]: string }
    ): Subscription;
    unsubscribe(id: string): void;
  }

  export const Stomp: {
    over(ws: any): Client;
    client(url: string, protocols?: any): Client;
    VERSIONS: {
      V1_0: string;
      V1_1: string;
      V1_2: string;
      supportedVersions(): string[];
    };
  };
}
