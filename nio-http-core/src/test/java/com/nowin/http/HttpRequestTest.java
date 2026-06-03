package com.nowin.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestTest {

    @Test
    void testGetBodyReturnsClone() {
        HttpRequest request = new HttpRequest();
        byte[] original = {1, 2, 3};
        request.setBody(original);

        byte[] returned = request.getBody();
        assertNotNull(returned);
        assertEquals(3, returned.length);
        returned[0] = 99;

        byte[] internal = request.getBody();
        assertEquals(1, internal[0], "Internal body should not be affected by mutating returned array");
    }

    @Test
    void testSetBodyClonesInput() {
        HttpRequest request = new HttpRequest();
        byte[] original = {1, 2, 3};
        request.setBody(original);

        original[0] = 99;

        byte[] internal = request.getBody();
        assertEquals(1, internal[0], "Internal body should not be affected by mutating original array");
    }

    @Test
    void testSetBodyNullIsSafe() {
        HttpRequest request = new HttpRequest();
        request.setBody(new byte[]{1, 2, 3});
        request.setBody(null);
        assertNull(request.getBody());
    }

    @Test
    void testGetPartsReturnsUnmodifiableList() {
        HttpRequest request = new HttpRequest();
        List<HttpPart> parts = new ArrayList<>();
        request.setParts(parts);

        List<HttpPart> returned = request.getParts();
        assertNotNull(returned);
        assertThrows(UnsupportedOperationException.class, () -> returned.add(null),
                "Returned parts list should be unmodifiable");
    }

    @Test
    void testSetPartsCopiesInput() {
        HttpRequest request = new HttpRequest();
        List<HttpPart> original = new ArrayList<>();
        request.setParts(original);

        original.add(null); // Mutate original after setting

        List<HttpPart> internal = request.getParts();
        assertEquals(0, internal.size(), "Internal parts should not be affected by mutating original list");
    }

    @Test
    void testSetPartsNullIsSafe() {
        HttpRequest request = new HttpRequest();
        request.setParts(new ArrayList<>());
        request.setParts(null);
        assertNull(request.getParts());
    }

    @Test
    void testGetBodyParametersReturnsUnmodifiableDeepCopy() {
        HttpRequest request = new HttpRequest();
        Map<String, List<String>> params = new HashMap<>();
        List<String> values = new ArrayList<>();
        values.add("v1");
        params.put("key", values);
        request.setBodyParameters(params);

        Map<String, List<String>> returned = request.getBodyParameters();
        assertNotNull(returned);
        assertEquals(1, returned.size());
        assertEquals("v1", returned.get("key").get(0));

        // Verify unmodifiable map
        assertThrows(UnsupportedOperationException.class, () -> returned.put("new", List.of()),
                "Returned map should be unmodifiable");

        // Verify unmodifiable inner list
        assertThrows(UnsupportedOperationException.class, () -> returned.get("key").add("v2"),
                "Inner lists should be unmodifiable");
    }

    @Test
    void testSetBodyParametersCopiesInput() {
        HttpRequest request = new HttpRequest();
        Map<String, List<String>> original = new HashMap<>();
        List<String> values = new ArrayList<>();
        values.add("v1");
        original.put("key", values);
        request.setBodyParameters(original);

        values.add("v2"); // Mutate original list after setting
        original.put("key2", List.of("v3")); // Add entry after setting

        Map<String, List<String>> internal = request.getBodyParameters();
        assertEquals(1, internal.size(), "Internal should have only original entries");
        assertEquals(1, internal.get("key").size(), "Internal list should not have v2 added");
    }

    @Test
    void testSetBodyParametersNullIsSafe() {
        HttpRequest request = new HttpRequest();
        request.setBodyParameters(new HashMap<>());
        request.setBodyParameters(null);
        assertNull(request.getBodyParameters());
    }

    @Test
    void testSetUriClearsPreviousQueryParams() {
        HttpRequest request = new HttpRequest();

        request.setUri("/path?a=1&b=2");
        assertEquals("1", request.getQueryParameter("a").orElse(null));
        assertEquals("2", request.getQueryParameter("b").orElse(null));

        // Change URI — old params should be cleared
        request.setUri("/path?c=3");
        assertEquals("3", request.getQueryParameter("c").orElse(null));
        assertTrue(request.getQueryParameter("a").isEmpty(), "Old query param 'a' should be cleared");
        assertTrue(request.getQueryParameter("b").isEmpty(), "Old query param 'b' should be cleared");
    }

    @Test
    void testGetBodyNullIsSafe() {
        HttpRequest request = new HttpRequest();
        assertNull(request.getBody());
    }

    @Test
    void testGetPartsNullIsSafe() {
        HttpRequest request = new HttpRequest();
        assertNull(request.getParts());
    }

    @Test
    void testGetBodyParametersNullIsSafe() {
        HttpRequest request = new HttpRequest();
        assertNull(request.getBodyParameters());
    }
}
