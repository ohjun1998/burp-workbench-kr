package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import kr.workbench.WorkbenchExtension;

/** Single public entry point. Burp's Multi-API contract injects legacy first. */
public final class BurpExtender implements IBurpExtender, BurpExtension {
    private IBurpExtenderCallbacks callbacks;
    private WorkbenchExtension workbench;
    private kr.payloadrunner.PayloadRunnerPanel payloadRunner;
    @Override public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks=callbacks;
        callbacks.setExtensionName("Burp Workbench KR 0.24.0");
    }
    @Override public void initialize(MontoyaApi api) {
        if(callbacks==null)throw new IllegalStateException("Burp Multi-API callbacks were not supplied. Use a current Burp release.");
        workbench=new WorkbenchExtension();
        workbench.initialize(api);
        payloadRunner=new kr.payloadrunner.PayloadRunnerPanel();payloadRunner.setMontoyaApi(api);payloadRunner.setScopePredicate(url->kr.workbench.WorkbenchScope.legacy(api,url.toString(),callbacks.isInScope(url)));
        java.awt.Component runnerPage=payloadRunner.initializeEmbedded(callbacks,workbench::selectPayloadRunnerPage);
        try {if(javax.swing.SwingUtilities.isEventDispatchThread())workbench.addPayloadRunnerPage(runnerPage);else javax.swing.SwingUtilities.invokeAndWait(()->workbench.addPayloadRunnerPage(runnerPage));}
        catch(Exception e){payloadRunner.extensionUnloaded();throw new IllegalStateException(e);}
        workbench.setPayloadImport(request->{
            var service=request.httpService();
            payloadRunner.importRequest(callbacks.getHelpers().buildHttpService(service.host(),service.port(),service.secure()),request.toByteArray().getBytes(),null);
        });
        payloadRunner.setEvidenceSink((service,rows)->workbench.capturePayloadEvidence(burp.api.montoya.http.HttpService.httpService(service.getHost(),service.getPort(),service.getProtocol().equalsIgnoreCase("https")),rows));
        api.extension().registerUnloadingHandler(payloadRunner::extensionUnloaded);
        api.extension().setName("Burp Workbench KR 0.24.0");
    }
}
