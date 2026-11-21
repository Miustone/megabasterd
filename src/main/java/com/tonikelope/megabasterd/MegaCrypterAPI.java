/*
 __  __                  _               _               _ 
|  \/  | ___  __ _  __ _| |__   __ _ ___| |_ ___ _ __ __| |
| |\/| |/ _ \/ _` |/ _` | '_ \ / _` / __| __/ _ \ '__/ _` |
| |  | |  __/ (_| | (_| | |_) | (_| \__ \ ||  __/ | | (_| |
|_|  |_|\___|\__, |\__,_|_.__/ \__,_|___/\__\___|_|  \__,_|
             |___/                                         
© Perpetrated by tonikelope since 2016
 */
package com.tonikelope.megabasterd;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.tonikelope.megabasterd.CryptTools.*;
import static com.tonikelope.megabasterd.MiscTools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.swing.JOptionPane;

/**
 *
 * @author tonikelope
 */
public class MegaCrypterAPI {

    public static final Set<String> PASS_CACHE = new HashSet<>();
    public static final Object PASS_LOCK = new Object();
    private static final Logger LOG = Logger.getLogger(MegaCrypterAPI.class.getName());
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private static String _rawRequest(String request, URL url_api) throws MegaCrypterAPIException {

        String response = null;

        HttpURLConnection con = null;

        try {

            if (MainPanel.isUse_proxy()) {

                con = (HttpURLConnection) url_api.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                    con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes("UTF-8")));
                }
            } else {

                con = (HttpURLConnection) url_api.openConnection();
            }

            con.setConnectTimeout(Transference.HTTP_CONNECT_TIMEOUT);
            con.setReadTimeout(Transference.HTTP_READ_TIMEOUT);
            con.setRequestProperty("Content-type", "application/json");
            con.setUseCaches(false);
            con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);
            con.setRequestMethod("POST");
            con.setDoOutput(true);

            try (var os = con.getOutputStream()) {
                os.write(request.getBytes("UTF-8"));
            }

            if (con.getResponseCode() != 200) {
                Logger.getLogger(MegaCrypterAPI.class.getName()).log(Level.WARNING, "{0} MegaCrypter HTTP {1} url={2}",
                        new Object[]{Thread.currentThread().getName(), con.getResponseCode(), url_api.toString()});

                MiscTools.drainAndCloseErrorStream(con);
                throw new MegaCrypterAPIException(-101);

            } else {

                try (InputStream is = con.getInputStream(); ByteArrayOutputStream byte_res = new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[MainPanel.DEFAULT_BYTE_BUFFER_SIZE];

                    int reads;

                    while ((reads = is.read(buffer)) != -1) {

                        byte_res.write(buffer, 0, reads);
                    }

                    response = new String(byte_res.toByteArray(), "UTF-8");

                    if (response.length() > 0) {

                        int mc_error;

                        if ((mc_error = MegaCrypterAPI.checkMCError(response)) != 0) {
                            throw new MegaCrypterAPIException(mc_error);

                        }
                    }
                }
            }

        } catch (IOException ex) {
            Logger.getLogger(MegaCrypterAPI.class.getName()).log(Level.SEVERE,
                    Thread.currentThread().getName() + " MegaCrypter IOException url=" + url_api, ex);
            throw new MegaCrypterAPIException(-101);
        } finally {

            if (con != null) {
                con.disconnect();
            }
        }

        return response;

    }

    public static String getMegaFileDownloadUrl(String link, String pass_hash, String noexpire_token, String sid, String reverse) throws IOException, MegaCrypterAPIException {
        HashMap<String, Object> reqMap = new HashMap<>();
        reqMap.put("m", "dl");
        reqMap.put("link", link);
        if (noexpire_token != null) {
            reqMap.put("noexpire", noexpire_token);
        }
        if (sid != null) {
            reqMap.put("sid", sid);
        }
        if (reverse != null) {
            reqMap.put("reverse", reverse);
        }
        String request = SHARED_MAPPER.writeValueAsString(reqMap);

        URL url_api = new URL(findFirstRegex("https?://[^/]+", link, 0) + "/api");

        String res = MegaCrypterAPI._rawRequest(request, url_api);

        HashMap res_map = SHARED_MAPPER.readValue(res, HashMap.class);

        String dl_url = (String) res_map.get("url");

        if (pass_hash != null) {
            try {
                String pass = (String) res_map.get("pass");

                byte[] decrypted_url = aes_cbc_decrypt_pkcs7(BASE642Bin(dl_url), BASE642Bin(pass_hash), BASE642Bin(pass));

                dl_url = new String(decrypted_url, "UTF-8");

            } catch (Exception ex) {
                throw new MegaCrypterAPIException(25);
            }
        }

        if (dl_url == null || "".equals(dl_url)) {
            throw new MegaCrypterAPIException(-101);
        }

        return dl_url;
    }

    public static String[] getMegaFileMetadata(String link, MainPanelView panel, String reverse) throws MegaCrypterAPIException, MalformedURLException, IOException {
        HashMap<String, Object> reqMap = new HashMap<>();
        reqMap.put("m", "info");
        reqMap.put("link", link);
        if (reverse != null) {
            reqMap.put("reverse", reverse);
        }
        String request = SHARED_MAPPER.writeValueAsString(reqMap);

        URL url_api = new URL(findFirstRegex("https?://[^/]+", link, 0) + "/api");

        String res = MegaCrypterAPI._rawRequest(request, url_api);

        ObjectMapper objectMapper = new ObjectMapper();

        objectMapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

        objectMapper.configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);

        HashMap res_map = objectMapper.readValue(res, HashMap.class);

        String rawName = (String) res_map.get("name");
        String fname = null;
        String fpath = null;
        String rawFpath = null;

        Object fpath_val = res_map.get("path");

        if (fpath_val instanceof String s) {
            rawFpath = s;
        }

        String file_size = String.valueOf(res_map.get("size"));

        String fkey = (String) res_map.get("key");

        String noexpire_token = null;

        Object expire_val = res_map.get("expire");

        if (expire_val instanceof String expStr) {
            String[] aux = expStr.split("#");
            if (aux.length > 1) {
                noexpire_token = aux[1];
            }
        }

        Object pass_val = res_map.get("pass");

        String pass = null;

        if (pass_val instanceof String pStr) {
            pass = pStr;
        }

        if (pass != null) {
            String[] pass_items = pass.split("#");

            if (pass_items.length != 4) {
                throw new MegaCrypterAPIException(25);
            }

            int iterations;
            try {
                iterations = Integer.parseInt(pass_items[0]);
            } catch (NumberFormatException nfe) {
                throw new MegaCrypterAPIException(25);
            }
            // Clamp PBKDF2 exponent to prevent remote CPU DoS (#788).
            if (iterations < 1 || iterations > 20) {
                throw new MegaCrypterAPIException(25);
            }

            byte[] key_check = BASE642Bin(pass_items[1]);

            byte[] salt = BASE642Bin(pass_items[2]);

            byte[] iv = BASE642Bin(pass_items[3]);

            String password;

            byte[] info_key = null;

            boolean bad_pass;

            Cipher decrypter;

            synchronized (PASS_LOCK) {

                LinkedList<String> pass_list = new LinkedList(PASS_CACHE);

                do {
                    bad_pass = true;

                    if ((password = pass_list.poll()) == null) {

                        final String[] entered = {null};
                        try {
                            MiscTools.GUIRunAndWait(() -> {
                                entered[0] = JOptionPane.showInputDialog(panel, I18n.tr("ui.input.megacrypter_password"));
                            });
                        } catch (Exception ex) {
                            // ignore
                        }
                        password = entered[0];
                    }

                    if (password != null) {

                        try {

                            info_key = PBKDF2HMACSHA256(password, salt, 1 << iterations, 256);

                            decrypter = genDecrypter("AES", "AES/CBC/PKCS5Padding", info_key, iv);

                            bad_pass = !java.security.MessageDigest.isEqual(info_key, decrypter.doFinal(key_check));

                            if (!bad_pass) {

                                PASS_CACHE.add(password);
                            }

                        } catch (Exception ex) {

                            bad_pass = true;
                        }
                    }

                } while (password != null && bad_pass);
            }

            if (bad_pass) {

                throw new MegaCrypterAPIException(25);

            } else {

                try {

                    decrypter = genDecrypter("AES", "AES/CBC/PKCS5Padding", info_key, iv);

                    byte[] decrypted_key = decrypter.doFinal(BASE642Bin(fkey));

                    fkey = Bin2UrlBASE64(decrypted_key);

                    decrypter = genDecrypter("AES", "AES/CBC/PKCS5Padding", info_key, iv);

                    byte[] decrypted_name = decrypter.doFinal(BASE642Bin(rawName));

                    fname = cleanFilename(new String(decrypted_name, "UTF-8"));

                    if (rawFpath != null) {
                        decrypter = genDecrypter("AES", "AES/CBC/PKCS5Padding", info_key, iv);
                        byte[] decrypted_fpath = decrypter.doFinal(BASE642Bin(rawFpath));

                        fpath = cleanFilePath(new String(decrypted_fpath, "UTF-8"));
                    }

                    pass = Bin2BASE64(info_key);

                } catch (Exception ex) {

                    throw new MegaCrypterAPIException(25);

                }
            }
        } else {
            fname = cleanFilename(rawName);
            if (rawFpath != null) {
                fpath = cleanFilePath(rawFpath);
            }
        }

        if (fpath != null) {
            fname = fpath + fname;
        }

        String file_data[] = {fname, file_size, fkey, pass, noexpire_token};

        return file_data;
    }

    private static int checkMCError(String data) {
        String error = findFirstRegex("\"error\" *: *([0-9-]+)", data, 1);

        return error != null ? Integer.parseInt(error) : 0;
    }

    private MegaCrypterAPI() {
    }
}
