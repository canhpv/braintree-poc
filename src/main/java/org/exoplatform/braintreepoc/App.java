package org.exoplatform.braintreepoc;

import static spark.Spark.get;
import static spark.Spark.post;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.braintreegateway.*;
import org.apache.commons.io.FileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.Route;

import com.braintreegateway.exceptions.NotFoundException;

public class App {

    static Logger log = LoggerFactory.getLogger(App.class);


    private static BraintreeGateway gateway = new BraintreeGateway(
            Environment.SANDBOX,
            "wjwxdjd4qczfnw52",
            "dx7k5z65wpdzdv3f",
            "c6f8d1bccf7ceb179cae7cd9439aa0b1"
    );

    private static String renderHtml(String pageName) {
        try {
            return FileUtils.readFileToString(new File(pageName));
        } catch (IOException e) {
            return "Couldn't find " + pageName;
        }
    }

    private static String renderHtml(String pageName, Map<String, String> params) {
        try {
            String template =  FileUtils.readFileToString(new File(pageName));
            for (Map.Entry<String, String> param :params.entrySet()) {
                template = template.replace("$" + param.getKey(), param.getValue());
            }
            return template;
        } catch (IOException e) {
            return "Couldn't find " + pageName;
        }
    }

    private static Plan findPlanById(String planId) {
        List<Plan> plans = gateway.plan().all();
        for (Plan plan : plans) {
            if (plan.getId().equals(planId)) {
                return plan;
            }
        }
        return null;
    }

    private static List<Plan> findActivePlans() {
        List<Plan> plans = new ArrayList<Plan>();
        for (Plan plan : gateway.plan().all()) {
            if (!plan.getId().startsWith("DISABLED")
                    && !plan.getId().startsWith("OLD")) {
                plans.add(plan);
            }
        }
        return plans;
    }

    public static void main(String[] args) {
        get(new Route("/") {
            @Override
            public Object handle(Request request, Response response) {
                response.type("text/html");



                List<Plan> plans = findActivePlans();
                String planBlocks = "";
                for (Plan plan : plans) {
                    Map planAttributes = new HashMap();
                    planAttributes.put("planId", plan.getId());
                    planAttributes.put("planName", plan.getName());
                    planAttributes.put("planDescription", plan.getDescription());
                    planAttributes.put("planPrice", ""+plan.getPrice());


                    List<AddOn> addons = plan.getAddOns();
                    String addonFields = "";
                    for (AddOn addon : addons) {
                        Map addonAttributes = new HashMap();
                        addonAttributes.put("addonId", addon.getId());
                        addonAttributes.put("name", addon.getName());
                        addonAttributes.put("description", addon.getDescription());
                        addonAttributes.put("amount", ""+addon.getAmount());
                        addonAttributes.put("currency", plan.getCurrencyIsoCode());

                        addonFields += renderHtml("views/addon.html", addonAttributes);
                    }
                    planAttributes.put("addons", addonFields)  ;
                    planBlocks += renderHtml("views/plan.html", planAttributes);
                }

                Map params = new HashMap();
                params.put("plans", planBlocks);

                String tenant = request.queryParams("tenant");
                if (tenant == null) tenant = "acme";
                params.put("tenant", tenant);
                return renderHtml("views/buy.html", params);
            }
        });

        post(new Route("/create_customer") {
            @Override
            public Object handle(Request request, Response response) {
                try {
                    CustomerRequest customerRequest = new CustomerRequest()
                            .firstName(request.queryParams("first_name"))
                            .lastName(request.queryParams("last_name"))
                            .customField("exo_cloud_tenant_name", request.queryParams("tenant_name"))
                            .creditCard()
                            .billingAddress()
                            .postalCode(request.queryParams("postal_code"))
                            .done()
                            .number(request.queryParams("number"))
                            .expirationMonth(request.queryParams("month"))
                            .expirationYear(request.queryParams("month"))
                            .cvv(request.queryParams("cvv"))
                            .done();

                    Result<Customer> result = gateway.customer().create(customerRequest);

                    response.type("text/html");
                    if (result.isSuccess()) {

                        List<Plan> plans = gateway.plan().all();
                        String text = "";
                        if (plans.size() > 0) {
                            text = "Click below to sign this Customer up for a plan:<br/>"
                                    +"<form action='/plan' method='GET' id='subscriptions-form'><input type='hidden' name='customer' value='"+result.getTarget().getId()+"'/>";
                            for (Plan plan : plans) {
                                text += "<input type='radio' name='plan' value='" +  plan.getId() + "'>"+plan.getName()+"</input><br/>";
                            }
                            text += "<input type='submit'/></form>";
                        }

                        return "<h2>Customer created with name: " + result.getTarget().getFirstName() + " " + result.getTarget().getLastName() + "</h2>" +
                                text;

                    } else {
                        return "<h2>Error: " + result.getMessage() + "</h2>";
                    }

                } catch (Exception e) {
                    return "<h1>error: " + e.getMessage() + "</h1>";
                }

            }

        });

        post(new Route("/buy") {
            @Override
            public Object handle(Request request, Response response) {
                try {
                    response.type("text/html");

                    String tenant = request.queryParams("tenant_name");
                    CustomerRequest customerRequest = new CustomerRequest()
                            .firstName(request.queryParams("first_name"))
                            .lastName(request.queryParams("last_name"))
                            .phone(request.queryParams("phone"))
                            .company(request.queryParams("organization"))
                            .email(request.queryParams("email"))
                            .customField("exo_cloud_tenant_name", tenant)
                            .creditCard()
                            .billingAddress()
                            .done()
                            .number(request.queryParams("number"))
                            .expirationMonth(request.queryParams("month"))
                            .expirationYear(request.queryParams("year"))
                            .cvv(request.queryParams("cvv"))
                            .done();

                    Result<Customer> customerResult = gateway.customer().create(customerRequest);


                    if (!customerResult.isSuccess()) {
                        return "<h2>Error creating customer: " + customerResult.getMessage() + "</h2>";
                    }

                    CreditCard card = customerResult.getTarget().getCreditCards().get(0);
                    String paymentMethodToken = card.getToken();

                    Map<String, Integer> addonsQuantities = new HashMap<String, Integer>();
                    java.util.Set<String> params = request.queryParams();
                    for (String param : params) {
                        if (param.startsWith("quantity_")) {
                            String addonId = param.substring("quantity_".length());
                            Integer quantity = request.queryMap(param).integerValue();
                            if (quantity > 0) {
                                addonsQuantities.put(addonId, quantity);
                            }
                        }
                    }


                    String planId = request.queryParams("plan");
                    SubscriptionRequest req = new SubscriptionRequest()
                            .paymentMethodToken(paymentMethodToken)
                            .planId(planId);


                    for (String addonId : addonsQuantities.keySet()) {
                        req.addOns().update(addonId).
                                quantity(addonsQuantities.get(addonId)).done();
                    }


                    Result<Subscription> subscriptionResult = gateway.subscription().create(req);

                    if (subscriptionResult.isSuccess()) {

                        return "<h1>Subscription Status</h1>" + subscriptionResult.getTarget().getStatus()
                                + "<a href=\"/cancel?id=" + customerResult.getTarget().getId() + "&subscription="+subscriptionResult.getTarget().getId()+"\"> Cancel</a>&nbsp;"
                                + "<a href=\"/upgrade\">Upgrade</a>";

                    } else {
                        return "<h2>Error creating subscription: " + subscriptionResult.getMessage() + "</h2>";
                    }


                } catch (Exception e) {
                    return "<h1>error: " + e.getMessage() + "</h1>";
                }

            }

        });


        get(new Route("/plan") {
            @Override
            public Object handle(spark.Request request, Response response) {
                try {

                    String customerId = request.queryParams("customer");
                    Customer customer = gateway.customer().find(customerId);
                    String planId = request.queryParams("plan");
                    Plan plan = findPlanById(planId);

                    String html = "<h1>You Selected : </h1>"
                            + "<strong>"+ plan.getName()+ "</strong><br/><em>"+ plan.getDescription() +"</em>"
                            + "<form action='/subscribe' method='GET' id='addon-form'><ul>"
                            + "<input type='hidden' name='customer' value="+ customerId +">"
                            + "<input type='hidden' name='plan' value="+ planId +">";

                    List<AddOn> addOns = plan.getAddOns();
                    for (AddOn addOn : addOns) {
                        html += "<li><input type='hidden' name='addon' value='"+addOn.getId()+"'/>"
                                + addOn.getDescription() + " <input type='text' name='quantity' value='5' size='4'> "
                                + " x " + plan.getCurrencyIsoCode() + addOn.getAmount() + "</li>";
                    }
                    html += "</ul><input type='submit' label='Subscribe'></form>";


                    return html;
                } catch (Exception e) {
                    return "<h1>error: " + e.getMessage() + "</h1>";
                }
            }
        });

        get(new Route("/subscribe") {
            @Override
            public Object handle(spark.Request request, Response response) {
                try {
                    Customer customer = gateway.customer().find(request.queryParams("customer"));
                    String paymentMethodToken = customer.getCreditCards().get(0).getToken();


                    SubscriptionRequest req = new SubscriptionRequest()
                            .paymentMethodToken(paymentMethodToken)
                            .planId(request.queryParams("plan"))
                            .addOns()
                            .update(request.queryParams("addon"))
                            .quantity(Integer.parseInt(request.queryParams("quantity")))
                            .done()
                            .done();

                    Result<Subscription> result = gateway.subscription().create(req);
                    response.type("text/html");

                    return "<h1>Subscription Status</h1>" + result.getTarget().getStatus()
                            + "<a href=\"/cancel?id=" + customer.getId() + "&subscription="+result.getTarget().getId()+"\"> Cancel</a>&nbsp;"
                            + "<a href=\"/upgrade\">Upgrade</a>";
                } catch (NotFoundException e) {
                    return "<h1>No customer found for id: " + request.queryParams("id") + "</h1>";
                }
            }
        });


        get(new Route("/subscriptions") {
            @Override
            public Object handle(spark.Request request, Response response) {
                try {
                    Customer customer = gateway.customer().find(request.queryParams("id"));
                    String paymentMethodToken = customer.getCreditCards().get(0).getToken();

                    SubscriptionRequest req = new SubscriptionRequest()
                            .paymentMethodToken(paymentMethodToken)
                            .planId(request.queryParams("plan"));

                    Result<Subscription> result = gateway.subscription().create(req);
                    response.type("text/html");

                    return "<h1>Subscription Status</h1>" + result.getTarget().getStatus()
                            + "<a href=\"/cancel?id=" + customer.getId() + "&subscription="+result.getTarget().getId()+"\"> Cancel</a>&nbsp;"
                            + "<a href=\"/upgrade\">Upgrade</a>";
                } catch (NotFoundException e) {
                    return "<h1>No customer found for id: " + request.queryParams("id") + "</h1>";
                }
            }
        });


        get(new Route("/cancel") {
            @Override
            public Object handle(spark.Request request, Response response) {
                try {

                    Result<Subscription> result = gateway.subscription().cancel(request.queryParams("subscription"));
                    Customer customer = gateway.customer().find(request.queryParams("id"));
                    response.type("text/html");
                    String html = "";
                    html = "<h1>Subscription Status</h1>" + result.getTarget().getStatus();
                    html += "<a href=\"/subscriptions?id=" + customer.getId() + "\">Add a Subscription</a>";
                    return html;
                } catch (NotFoundException e) {
                    return "<h1>No customer found for id: " + request.queryParams("id") + "</h1>";
                }
            }
        });

    }
}
