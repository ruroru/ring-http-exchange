(ns ring-http-exchange.ssl-utils
  (:import [java.security KeyStore Security KeyPairGenerator]
           [java.security.cert X509Certificate]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.asn1.x500 X500Name]
           [org.bouncycastle.cert.jcajce JcaX509CertificateConverter JcaX509v3CertificateBuilder]
           [org.bouncycastle.operator.jcajce JcaContentSignerBuilder]
           [java.math BigInteger]
           [java.util Date]))

(Security/addProvider (BouncyCastleProvider.))

(defn- generate-mock-certificate
  [key-pair]
  (let [now (Date.)
        not-after (Date. (+ (.getTime now) (* 24 60 60 1000)))
        serial-number (BigInteger/valueOf (System/currentTimeMillis))
        subject (X500Name. "CN=Mock Certificate, O=Test Org, C=US")
        issuer subject
        public-key (.getPublic key-pair)
        private-key (.getPrivate key-pair)

        cert-builder (JcaX509v3CertificateBuilder.
                       issuer
                       serial-number
                       now
                       not-after
                       subject
                       public-key)

        signer (.build (JcaContentSignerBuilder. "SHA256WithRSAEncryption")
                       private-key)

        cert-holder (.build cert-builder signer)
        converter (JcaX509CertificateConverter.)
        _ (.setProvider converter "BC")]
    (.getCertificate converter cert-holder)))

(defn- create-mock-keystore
  [password]
  (let [keystore (KeyStore/getInstance "PKCS12" "BC")
        password (.toCharArray password)

        key-gen (KeyPairGenerator/getInstance "RSA" "BC")
        _ (.initialize key-gen 2048)
        key-pair (.generateKeyPair key-gen)
        certificate (generate-mock-certificate key-pair)
        cert-chain (into-array X509Certificate [certificate])]

    (.load keystore nil password)

    (.setKeyEntry keystore
                  "mock-key"
                  (.getPrivate key-pair)
                  password
                  cert-chain)

    {:keystore keystore
     :password password}))

(defn get-keystore-manager [password]
  (:keystore (create-mock-keystore password)))
