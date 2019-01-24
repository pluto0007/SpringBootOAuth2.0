package com.example.demo;


import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.AuthoritiesExtractor;
import org.springframework.boot.autoconfigure.security.oauth2.resource.UserInfoTokenServices;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.filter.OAuth2ClientAuthenticationProcessingFilter;
import org.springframework.security.oauth2.client.filter.OAuth2ClientContextFilter;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableOAuth2Client;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.filter.CompositeFilter;

@SpringBootApplication
@Controller
@EnableOAuth2Client
@EnableAuthorizationServer
@Order(SecurityProperties.BASIC_AUTH_ORDER - 2)

public class SimpleApplicationWithEnableOAuth2SssoErrorPage extends WebSecurityConfigurerAdapter
{
    @Autowired
    private OAuth2ClientContext oauth2ClientContext;


    @Override
    protected void configure(HttpSecurity http) throws Exception
    {
        http
            .antMatcher("/**")
            .authorizeRequests()
            .antMatchers("/", "/login**", "/webjars/**", "/error**")
            .permitAll()
            .anyRequest()
            .authenticated()
            .and().exceptionHandling()
            .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/"))
            .and().logout().logoutSuccessUrl("/").permitAll()
            .and().csrf().csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .and().addFilterBefore(ssoFilter(), BasicAuthenticationFilter.class);

    }


    private Filter ssoFilter()
    {
        CompositeFilter filter = new CompositeFilter();
        List<Filter> filters = new ArrayList<>();
        filters.add(ssoFilter(facebook(), "/login/facebook"));
        filters.add(ssoFilter(github(), "/login/github"));
        filter.setFilters(filters);
        return filter;
    }


    private Filter ssoFilter(ClientResources client, String path)
    {
        OAuth2ClientAuthenticationProcessingFilter filter = new OAuth2ClientAuthenticationProcessingFilter(path);
        OAuth2RestTemplate template = new OAuth2RestTemplate(client.getClient(), oauth2ClientContext);
        filter.setRestTemplate(template);
        UserInfoTokenServices tokenServices =
            new UserInfoTokenServices(
                client.getResource().getUserInfoUri(), client.getClient().getClientId());
        tokenServices.setRestTemplate(template);
        filter.setTokenServices(tokenServices);
        return filter;
    }


    
/*     * To explicitly support the redirects from our app to Facebook.
     *  This is handled in Spring OAuth2 with a servlet Filter, and 
     *  the filter is already available in the application context 
     *  because we used @EnableOAuth2Client. All that is needed is
     *  to wire the filter up so that it gets called in the right
     *  order in our Spring Boot application. To do that we need
     *  a FilterRegistrationBean*/

    @Bean
    public FilterRegistrationBean<OAuth2ClientContextFilter> oauth2ClientFilterRegistration(OAuth2ClientContextFilter filter)
    {
        FilterRegistrationBean<OAuth2ClientContextFilter> registration = new FilterRegistrationBean<OAuth2ClientContextFilter>();
        registration.setFilter(filter);
        registration.setOrder(-100);
        return registration;
    }


    @Bean
    @ConfigurationProperties("github")
    public ClientResources github()
    {
        return new ClientResources();
    }


    @Bean
    @ConfigurationProperties("facebook")
    public ClientResources facebook()
    {
        return new ClientResources();
    }


    @RequestMapping({"/user", "/me"})
    public Map<String, String> user(Principal principal)
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", principal.getName());
        return map;
    }


    @RequestMapping("/unauthenticated")
    public String unauthenticated()
    {
        return "redirect:/?error=true";
    }

    @Configuration
    protected static class ServletCustomizer
    {
        @Bean
        public WebServerFactoryCustomizer<ConfigurableWebServerFactory> customizer()
        {
            return container -> {
                container.addErrorPages(new ErrorPage(HttpStatus.UNAUTHORIZED, "/unauthenticated"));
            };
        }
    }
    /*
     * Spring Boot has provided an easy extension point:
     *  if we declare a @Bean of type AuthoritiesExtractor
     *  it will be used to construct the authorities 
     *  (typically "roles") of an authenticated user.
     *  We can use that hook to assert the the user is in
     *  the correct organization, and throw an exception if not:
     *  */

    @Bean
    public AuthoritiesExtractor authoritiesExtractor(OAuth2RestOperations template)
    {
        return map -> {
            String url = (String) map.get("organizations_url");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> orgs = template.getForObject(url, List.class);
            if (orgs
                .stream()
                .anyMatch(org -> "spring-projects".equals(org.get("login"))))
            {
                return AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_USER");
            }
            throw new BadCredentialsException("Not in Spring Projects origanization");
        };
    }


    @Bean
    public OAuth2RestTemplate oauth2RestTemplate(OAuth2ProtectedResourceDetails resource, OAuth2ClientContext context)
    {
        return new OAuth2RestTemplate(resource, context);
    }


    public static void main(String[] args)
    {
        SpringApplication.run(SimpleApplicationWithEnableOAuth2SssoErrorPage.class, args);
    }

}

