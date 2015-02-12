#include <Metro.h>

#include <Ethernet.h>
#include <SPI.h>
#include <signal.h>
#include <ArduinoJson.h>

byte mac[] = { 0x98, 0x4F, 0xEE, 0x01, 0x3E, 0x57 };
char host[] = "galileo-cabinet.herokuapp.com"; // "192.168.0.5";
int port = 80; // 9000;
char cabinet[] = "a00j000000484gE";
char refrig[] = "a03j0000002RKof";

int ledPin = 2;
int buttonPin = 3;
int tempPin = A0;

int B = 3975;  //B value of the thermistor


Metro tick = Metro(30000);

EthernetClient client;


float temp() {
  int a = analogRead(tempPin);
  float resistance = (float)(1023-a)*10000/a;
  float temperature = 1/(log(resistance/10000)/B+1/298.15)-273.15;
  return temperature;
}

int button() {
  return digitalRead(buttonPin);
}

void sendReadings() {
  client.stop();
  
  StaticJsonBuffer<200> jsonBuffer;

  JsonObject& root = jsonBuffer.createObject();
  root["cabinet"] = cabinet;
  root["refrig"] = refrig;
  root["temp"] = temp();
  root["button"] = button();
  
  char json[256];
  root.printTo(json, sizeof(json));
  
  Serial.print("Sending data: ");
  Serial.println(json);
  
  char contentLengthHeader[64];
  sprintf(contentLengthHeader, "Content-Length: %d", strlen(json));
  
  if (client.connect(host, port)) {
    client.println("POST / HTTP/1.1");
    client.println("Host: " + String(host));
    client.println("Content-Type: application/json");
    client.println(contentLengthHeader);
    client.println();
    client.println(json);
  }
  else {
    Serial.println("connection failed");
  }
}

void setup() {
  signal(SIGPIPE, SIG_IGN); // workaround an ethernet issue: https://communities.intel.com/thread/46109
  Serial.begin(9600);
  Ethernet.begin(mac);
  pinMode(ledPin, OUTPUT);
  pinMode(buttonPin, INPUT);
  sendReadings();
}

void loop() {
  // turn the led on if the switch is down
  digitalWrite(ledPin, button());
  
  if (tick.check() == 1) {
    sendReadings();
  }
}
